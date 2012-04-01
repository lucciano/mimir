(ns mimir.well
  (:use [clojure.set :only (intersection map-invert rename-keys difference union join)]
        [clojure.tools.logging :only (debug info warn error spy)]
        [clojure.walk :only (postwalk postwalk-replace)])
  (:refer-clojure :exclude [assert])
  (:gen-class))

(defn create-net []
  {:productions #{}
   :working-memory #{}
   :predicates {}
   :alpha-network {}
   :beta-join-nodes {}})

(def ^:dynamic *net* (atom (create-net)))

(defn working-memory [] (:working-memory @*net*))

(defn alpha-network [] (:alpha-network @*net*))

(defn beta-join-nodes [] (:beta-join-nodes @*net*))

(defn triplets
  ([x] (triplets x identity))
  ([[x & xs] post-fn]
     (when x
       (if ((some-fn
             sequential? map? set? string?) x) (cons x (triplets xs post-fn))
           (cons (post-fn (cons x (take 2 xs)))
                 (triplets (drop 2 xs) post-fn))))))

(defn triplet? [x]
  (and (sequential? x) (= 3 (count x))))

(defn is-var? [x]
  (if (symbol? x)
    (let [s (name x)]
      (or (.startsWith s "?")
          (re-matches #"[A-Z]+" s)))
    false))

(defn quote-non-vars [rhs]
  (postwalk #(if (and (symbol? %)
                      (not (is-var? %))) (list 'quote %) %) rhs))

(defn vars [x]
  (let [vars (transient [])]
    (postwalk #(when (is-var? %) (conj! vars %)) x)
    (persistent! vars)))

(defn quote-fact [t]
  (list 'quote t))

(defn expand-rhs [t]
  (cons 'mimir.well/assert t))

(defn ellipsis
  ([x] (ellipsis 5 x))
  ([n x]
     (str (seq (take n x)) (when (< n (count x))
                             (str " ...  [total: " (count x) "]")))))

(defmacro rule [name & body]
  (let [[lhs _ rhs] (partition-by '#{=>} body)
        [doc lhs] (split-with string? lhs)
        lhs (triplets lhs)
        rhs (triplets rhs expand-rhs)]
    `(let [f# (defn ~name
                ([] (~name {}))
                ([~'args] (map #(%) (~name (working-memory) ~'args)))
                ([~'wm ~'args]
                   (debug "rule" '~name '~*ns*)
                   (doall
                    (for [vars# (binding [*ns* '~*ns*]
                                  (check-rule '~(vec lhs) ~'wm ~'args))
                          :let [{:syms ~(vars rhs)} vars#]]
                      #(do
                         (debug "rhs" vars#)
                         ~@rhs)))))]
       (debug "defining rule" '~name)
       (alter-meta! f# merge {:lhs '~lhs :rhs '~rhs :doc ~(apply str doc)})
       (swap! *net* update-in [:productions] conj f#)
       f#)))

(defmacro with-cache [cache-name key & f]
  (let [cache-name (keyword cache-name)]
    `(let [key# ~key]
       (if-not (contains? ('~cache-name @*net*) key#)
         (let [v# (do ~@f)]
           (swap! *net* assoc-in ['~cache-name key#] v#)
           v#)
         (get-in @*net* ['~cache-name key#])))))

(defn join-on [x y]
  (intersection (set (vars x)) (set (vars y))))

(defn var-sym [x]
  (symbol (str "?" x)))

(defn var-to-index [c]
  (loop [[v & vs] (vars c)
         acc {}]
    (if v
      (recur vs (if (acc v)
                  acc
                  (assoc acc v (var-sym (inc (count acc))))))
      acc)))

(defn ordered-vars [c]
  (->> (var-to-index c) vals sort vec))

(defn predicate-for [c]
  (with-cache predicate c
    (let [args (ordered-vars c)
          src `(fn ~args ~c)]
      (with-meta (eval src) {:src src}))))

(defn match-using-predicate [c wme]
  (try
    (let [predicate (predicate-for c)]
      (when (predicate wme)
        (debug " evaluated to true" wme)
        {'?1 wme}))
    (catch RuntimeException e
      (debug " threw non fatal" e))))

(defn match-triplet [c wme]
  (loop [[v & vs] wme [t & ts] c m {}]
    (if v
      (condp some [t]
        #{v} (recur vs ts m)
        is-var? (recur vs ts (assoc m t v))
        nil)
      (do
        (debug " evaluated to true" wme)
        m))))

(defn predicate? [c]
  (-> c first resolve boolean))

(defn multi-var-predicate? [c]
  (and (predicate? c) (> (count (ordered-vars c)) 1)))

(defn multi-var-predicate-placeholder [c]
  (let [args (ordered-vars c)]
    (debug " more than one argument, needs beta network")
    (with-meta (zipmap args (repeat (predicate-for c))) {:args args})))

(defn match-wme [c wme]
  (condp some [c]
    predicate? (match-using-predicate c wme)
    triplet? (match-triplet c wme)
    nil))

(defn ^:private wm-crud [action test msg fact]
  (when (test (working-memory) fact)
    (debug msg " fact" fact)
    (swap! *net* update-in [:working-memory] action fact)
    (doseq [c (keys (:alpha-network @*net*))
            :let [match (match-wme c fact)]
            :when match]
      (debug " alpha network change" match)
      (swap! *net* update-in [:alpha-network] #(merge-with action % {c match}))))
  fact)

(defn fact [fact]
  (wm-crud conj (complement contains?) "asserting" fact))

(defn retract* [fact]
  (wm-crud disj contains? "retracting" fact))

(defmacro facts [& wms]
  (when wms
    `(doall
      (for [wm# ~(vec (triplets wms quote-fact))]
        (fact wm#)))))

(defn matching-wmes
  ([c] (matching-wmes c (working-memory)))
  ([c wm]
     (debug "condition" c)
     (if (multi-var-predicate? c)
       #{(multi-var-predicate-placeholder c)}
       (->> wm
            (map #(match-wme c %))
            (remove nil?)
            (set)))))

(defn alpha-network-lookup [c wm]
  (with-cache alpha-network c
    (matching-wmes c wm)))

(defn alpha-memory
  ([c] (alpha-memory c (working-memory)))
  ([c wm]
     (let [var-to-index (var-to-index c)
           vars-by-index (map-invert var-to-index)]
       (->> (alpha-network-lookup (postwalk-replace var-to-index c) wm)
            (map #(rename-keys (with-meta % (postwalk-replace vars-by-index (meta %))) vars-by-index))))))

(defn cross [left right]
  (debug " nothing to join on, treating as or")
  (into #{}
        (for [x left y right]
          (merge x y))))

(defn multi-var-predicate-node? [am]
  (if (and (seq? am) (= 1 (count am)))
    (fn? (-> am first first val))
    false))

(defn all-different? [xs]
  (= xs (distinct xs)))

(defn all-different [& xs]
  (all-different? xs))

(defn different [f & xs]
  (all-different? (map f xs)))

(defn unique [xs]
  (= xs (sort xs)))

(defn same*
  ([test pred xs]
     (test (for [x xs y (remove #{x} xs)]
             (pred x y)))))

(defn not-same [pred & xs]
  (same* (partial not-any? true?) pred xs))

(defn same [pred & xs]
  (same* (partial every? true?) pred xs))

(defn permutations [n coll]
  (if (zero? n)
    '(())
    (->> (permutations (dec n) coll)
         (mapcat #(map (partial conj %) coll))
         (filter #(all-different? %)))))

(defn ^:private build-args [base wmes]
  (loop [idx 0 wmes wmes base base]
    (if (seq wmes)
      (if (is-var? (base idx))
        (recur (inc idx) (next wmes) (assoc base idx (first wmes)))
        (recur (inc idx) wmes base))
      base)))

(defn deal-with-multi-var-predicates [c1-am c2-am join-on]
  (let [pred (-> c2-am first first val)
        args (-> c2-am first meta :args)
        src (-> pred meta :src)
        needed-args (remove join-on args)
        permutated-wm (permutations (count needed-args) (working-memory))]
    (debug " multi-var-predicate")
    (debug " args" args)
    (debug " known args" join-on "- need to find" (count needed-args))
    (debug " permutations of wm" (ellipsis permutated-wm))
    (mapcat
     (fn [m]
       (let [known-args (select-keys m join-on)
             base-args (replace known-args args)]
         (for [wmes permutated-wm
               :when (try
                       (apply pred (build-args base-args wmes))
                       (catch RuntimeException e
                         (debug " threw non fatal" e)))]
           (merge m (zipmap needed-args wmes)))))
     c1-am)))

(defn beta-join-node [c1 c2 c1-am wm]
  (let [c2-am (alpha-memory c2 wm)]
    (with-cache beta-join-nodes [c1-am c2-am]
      (let [join-on (join-on (-> c1-am first keys) c2)]
        (debug "join" join-on)
        (debug "  left" (ellipsis c1-am))
        (debug " right" (ellipsis c2-am))
        (let [result (cond
                      (multi-var-predicate-node? c2-am) (deal-with-multi-var-predicates c1-am c2-am join-on)
                      (empty? join-on) (cross c1-am c2-am)
                      :else (join c1-am c2-am))]
          (debug "result" (ellipsis result))
          result)))))

(defn dummy-beta-join-node [c wm args]
  (beta-join-node '() c #{args} wm))

(defn check-rule
  ([cs wm] (check-rule cs wm {}))
  ([cs wm args]
     (debug "conditions" cs)
     (loop [[c1 & cs] cs
            matches (dummy-beta-join-node c1 wm args)]
       (if-not cs
         matches
         (let [c2 (first cs)]
           (recur cs (beta-join-node c1 c2 matches wm)))))))

(defmacro assert
  ([fact]
     `(let [fact# (list ~@(quote-non-vars fact))]
        (fact fact#)))
  ([id rel attr]
     `(assert ~(list id rel attr))))

(defmacro retract
  ([fact]
     `(let [fact# (list ~@(quote-non-vars fact))]
        (retract* fact#)))
  ([id rel attr]
     `(retract ~(list id rel attr))))

(defn run-once [wm productions]
  (->> productions
       (mapcat #(% wm {}))
       (map #(%))
       set))

(defn run
  ([] (run *net*))
  ([net]
     (binding [*net* net]
       (loop [wm (working-memory)
              productions (:productions @net)
              acc #{}]
         (let [acc (union (run-once wm productions) acc)]
           (if (seq (difference (working-memory) wm))
             (recur (working-memory) productions acc)
             acc))))))

(defn reset []
  (reset! *net* (create-net)))

(defn version []
  (-> "project.clj" clojure.java.io/resource
      slurp read-string (nth 2)))

(defn -main [& args]
  (println)
  (println "Welcome to Mímir |" (version) "| Copyright © 2012 Håkan Råberg")
  (println)
  (require 'clojure.main)
  (clojure.main/repl :init #(in-ns 'mimir.well)))
