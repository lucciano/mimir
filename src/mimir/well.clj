(ns mimir.well
  (:use [clojure.set :only (intersection map-invert rename-keys difference union)]
        [clojure.tools.logging :only (debug info warn error)]
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

(defn triplets
  ([x] (triplets x identity))
  ([[x & xs] post-fn]
     (when x
       (if (sequential? x) (cons x (triplets xs post-fn))
           (cons (post-fn (cons x (take 2 xs)))
                 (triplets (drop 2 xs) post-fn))))))

(defn triplet? [x]
  (and (sequential? x) (= 3 (count x))))

(defn is-var? [x]
  (and (symbol? x)
       (boolean (re-find #"^(\?.+|<.+>)$" (name x)))))

(defn quote-non-vars [rhs]
  (postwalk #(if (and (symbol? %)
                      (not (is-var? %))) (list 'quote %) %) rhs))

(defn vars [x]
  (->> x flatten (filter is-var?) vec))

(defn quote-fact [t]
  (list 'quote t))

(defn expand-rhs [t]
  (cons 'mimir.well/assert t))

(defmacro rule [name & body]
  (let [[lhs _ rhs] (partition-by '#{=>} body)
        [doc lhs] (split-with string? lhs)
        lhs (triplets lhs)
        rhs (triplets rhs expand-rhs)]
    `(let [f# (defn ~name
                ([] (~name (working-memory)))
                ([~'wm]
                   (debug "rule" '~name '~*ns*)
                   (doall
                    (for [vars# (binding [*ns* '~*ns*]
                                  (check-rule '~(vec lhs) ~'wm))
                          :let [{:syms ~(vars rhs)} vars#]]
                      #(do
                         (debug "rhs" vars#)
                         ~@rhs)))))]
       (debug "defining rule" '~name)
       (alter-meta! f# merge {:lhs '~lhs :rhs '~rhs :doc ~(apply str doc)})
       (swap! *net* update-in [:productions] conj f#)
       f#)))

(defmacro defrule [name & body]
  `(rule ~name ~@body))

(defmacro with-cache [cache-name key f]
  (let [cache-name (keyword cache-name)]
    `(let [key# ~key]
       (if-not (contains? ('~cache-name @*net*) key#)
         (let [v# ~f]
           (swap! *net* assoc-in ['~cache-name key#] v#)
           v#)
         (get-in @*net* ['~cache-name key#])))))

(defn join-on [x y]
  (intersection (set (vars x)) (set (vars y))))

(defn var-sym [x]
  (symbol (str "?" x)))

(defn var-to-index [c]
  (loop [[v & vs] (-> c vars flatten)
         acc {}]
    (if v
      (recur vs (if (acc v)
                  acc
                  (assoc acc v (var-sym (inc (count acc))))))
      acc)))

(defn ordered-vars [c]
  (->> (var-to-index c) vals sort vec))

(defn predicate-for [c]
  (with-cache predicate [c *ns*]
    (let [args (ordered-vars c)
          src `(fn ~args ~c)]
      (with-meta (eval src) {:src src}))))

(defn match-using-predicate [c wm]
  (try
    (debug "predicate" c wm)
    (let [args (ordered-vars c)
          predicate (predicate-for c)]
      (if (= 1 (count args))
        (when (predicate wm)
          (debug " evaluated to true")
          {(first args) wm})
        (do
          (debug " more than one argument, needs beta network")
          (with-meta (apply hash-map (interleave args (repeat predicate))) {:args args}))))
    (catch RuntimeException e
      (debug " threw non fatal" e))))

(defn match-wme [c wme]
  (condp some [c]
    (comp
     resolve
     first) (match-using-predicate c wme)
     triplet? (loop [[v & vs] wme [t & ts] c m {}]
                (if v
                  (condp some [t]
                    #{v} (recur vs ts m)
                    is-var? (recur vs ts (assoc m t v))
                    nil)
                  m))
     (= c wme)))

(defn fact [fact]
  (when-not (contains? (working-memory) fact)
    (debug "asserting fact" fact)
    (swap! *net* update-in [:working-memory] conj fact)
    (doseq [c (keys (:alpha-network @*net*))
            :let [match (match-wme c fact)]
            :when match]
      (debug "inserting into alpha network" match)
      (swap! *net* update-in [:alpha-network] #(merge-with conj % {c match}))))
  fact)

(defmacro facts [& wms]
  (when wms
    `(doall
      (for [wm# ~(vec (triplets wms quote-fact))]
        (fact wm#)))))

(defmacro ! [& wms]
  `(facts ~@wms))

(defn matching-wmes
  ([c] (matching-wmes c (working-memory)))
  ([c wm]
     (debug "condition" c)
     (->> wm
          (map #(match-wme c %))
          (remove nil?)
          (set))))

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

(defn join-key [x join-on]
  (when-let [keys (seq (select-keys x join-on))]
    (apply vec keys)))

(defn prepare-join [x join-on]
  (sort-by first (map #(list (join-key % join-on) %) x)))

(defn join [left right join-on]
  (debug "join")
  (let [[left right] (sort-by count (map #(prepare-join % join-on) [left right]))]
    (loop [[[lk lv] & l-rst :as l] left
           [[rk rv] & r-rst :as r] right
           acc #{}]
      (debug "joining" lk rk lv rv)
      (if-not (and lk rk)
        acc
        (condp some [(compare lk rk)]
          neg? (recur l-rst r acc)
          pos? (recur l r-rst acc)
          (recur l-rst r-rst (conj acc (merge lv rv))))))))

(defn cross [left right]
  (let [cross (into #{}
                    (for [x left y right]
                      (merge x y)))]
    (debug "nothing to join on, treating as or" cross)
    cross))

(defn multi-var-predicate? [c2-am]
  (if (and (seq? c2-am) (= 1 (count c2-am)))
    (fn? (-> c2-am first first val))
    false))

(defn all-different? [xs]
  (= xs (distinct xs)))

(defn permutations [n coll]
  (if (zero? n)
    '(())
    (->> (permutations (dec n) coll)
         (mapcat #(map (partial conj %) coll))
         (filter all-different?))))

(defn deal-with-multi-var-predicates [c1-am c2-am join-on]
  (let [pred (-> c2-am first first val)
        args (-> c2-am first meta :args)
        src (-> pred meta :src)
        permutated-wm (permutations (- (count args) (count join-on)) (working-memory))]
    (debug "multi-var-preducate " src)
    (debug "args " args)
    (debug "perm-wm" (take 5 permutated-wm) "...")
    (->> c1-am
         (mapcat
          (fn [m]
            (let [real-args (replace (select-keys m join-on) args)
                  unbound-vars (filter is-var? real-args)]
              (for [wm permutated-wm
                    :let [vars (apply hash-map (interleave unbound-vars wm))
                          expanded-args (replace vars real-args)]]
                (try
                  (when (apply pred expanded-args)
                    (merge m vars))
                  (catch RuntimeException e
                    (debug " threw non fatal" e)))))))
         (remove nil?))))

(defn beta-join-node [c1 c2 c1-am wm]
  (let [c2-am (alpha-memory c2 wm)]
    (with-cache beta-join-nodes [c1-am c2-am]
      (let [join-on (join-on (-> c1-am first keys) c2)]
        (debug "join" c1-am c2-am join-on)
        (cond
         (multi-var-predicate? c2-am) (deal-with-multi-var-predicates c1-am c2-am join-on)
         (empty? join-on) (cross c1-am c2-am)
         :else (join c1-am c2-am join-on)))))) ; (clojure.set/join c1-am c2-am)

(defn check-rule
  ([cs wm]
     (debug "conditions" cs)
     (loop [[c1 & cs] cs
            matches (beta-join-node '() c1 #{{}} wm)]
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

(defn run-once [wm productions]
  (->> productions
       (mapcat #(% wm))
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

(defmacro match? [& expected]
  `(= (set ~(vec (triplets expected quote-fact))) (set (run))))

(defmacro ? [& expected]
  `(match? ~@expected))

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
