(ns mimir.test.parse
  (:use [mimir.parse]))

;; This is not yet a real test, just experiments and examples in various broken states.
;; This grammar is from the "Transforming the tree" section of Instaparser:
;; https://github.com/Engelberg/instaparse#transforming-the-tree

(def expression (create-parser
                 :expr      :add-sub
                 :<add-sub> #{:mul-div :add :sub}
                 :add       [:add-sub "+" :mul-div]
                 :sub       [:add-sub "-" :mul-div]
                 :<mul-div> #{:term :mul :div}
                 :mul       [:mul-div "*" :term]
                 :div       [:mul-div "/" :term]
                 :<term>    #{:number ["(" :add-sub ")"]}
                 :number    #"[0-9]+"))

(expression "1")

;; Stopped working after starting from first rule only, now needs left recurision.
;(expression "1/2")
;(expression "2+5*2")
;; Doesn't work yet
;(expression "1+2+3")
;; Need to handle left recursion, tree from instaparse:
;; [:expr [:add [:add [:number "1"] [:number "2"]] [:number "3"]]]

;;(expression "1-2/(3-4)+5*6")

;; PEG example from http://bford.info/pub/lang/packrat-icfp02-slides.pdf
;; Additive → Multitive '+' Additive
;; | Multitive
;; Multitive → Primary '*' Multitive
;; | Primary
;; Primary → '(' Additive ')'
;; | Decimal
;; Decimal → '0' | ... | '9'

(def peg-expression (create-parser
                     {:suppress-tags true}

                     :additive  (choice [:multitive #"[+-]" :additive]
                                        :multitive) op
                     :multitive (choice [:primary #"[*/]" :multitive]
                                        :primary) op
                     :primary   (choice ["(" :additive ")"]
                                        :decimal)
                     :decimal   #"[0-9]+" read-string))

;; This gets wrong precedence, regardless of using choice / OrderedSet or not. So something else.
;; This also seems right associtive?
;; Should return 33.
(peg-expression "1-2/(3-4)+5*6")

(peg-expression "2+5*2")
(peg-expression "2+5*2" :grammar-actions false :suppress-tags false)

;; A different expression grammar from:
;; http://www.cs.umd.edu/class/fall2002/cmsc430/lec4.pdf

;; Left recursive
;; 1 <goal> ::= <expr>
;; 2 <expr> ::= <expr> + <term>
;; 3 | <expr> - <term>
;; 4 | <term>
;; 5 <term> ::= <term> * <factor>
;; 6 | <term> = <factor>
;; 7 | <factor>
;; 8 <factor> ::= number
;; 9 | id

(def left-recursive (create-parser
                     :goal   :expr
                     :expr   #{[:expr #"\+" :term]
                               [:expr #"-" :term]
                               :term}
                     :term   #{[:term #"\*" :factor]
                               [:term #"/" :factor]
                               :factor}
                     :factor #{#"[0-9]+" #"\w+"}))
;; Doesn't work
;; (left-recursive "x - 2 * y")

;; Right recursive
;; 1 <goal> ::= <expr>
;; 2 <expr> ::= <term> + <expr>
;; 3 | <term> - <expr>
;; 4 | <term>
;; 5 <term> ::= <factor> * <term>
;; 6 | <factor> / <term>
;; 7 | <factor>
;; 8 <factor> ::= number
;; 9 | id

(def right-recursive (create-parser
                      {:suppress-tags true}

                      :goal   :expr
                      :expr   #{[:term #"[+-]" :expr]
                                :term} op
                      :term   #{[:factor #"[*/]" :term]
                                ["(" :expr ")"]
                                :factor} op
                      :factor #{#"[0-9]+" #"\w+"} #'*read-string*))

(let [x 1 y 3]
  (right-recursive "x - 2 * y" :read-string (dynamic-reader)))

;; Extended the grammar to support parenthesis, now this fails the same way as peg-expression:
(right-recursive "1-2/(3-4)+5*6")
;; Gives -27, should be 33.

;; Here's a simpler tree that fails:
;; Gives -4, should be 2.
(right-recursive "1-2+3")
;; Actually, the slides says: "Note: This grammar is right-associative." !

;; But the tree is much nicer, and could hint to the answer:
(right-recursive "1-2/(3-4)+5*6" :grammar-actions false :suppress-tags false)

;; Check that memoization actually works.
(comment
  (let [x 1 y 3]
    (time (right-recursive "x - 2 * y" :read-string (dynamic-reader) :memoize false))))


;; This variant handles left-associative without being left recursive:
;; http://stackoverflow.com/questions/6148705/relation-between-grammar-and-operator-associativity?rq=1

;; Expr  ::= Term  ( ("+"|"-") Term )*;
;; Term  ::= Factor ( ( "*" | "/" ) Factor )* ;
;; Factor ::= INTEGER | "(" Expr ")"

;; But the tree is not very clear:
(defn expr-eval [& x]
  (let [x (apply maybe-singleton x)]
    (if (sequential? x)
      (reduce (fn [x [op y]] ((fun op) x y)) x)
      x)))

(def stackoverflow (create-parser
                      {:suppress-tags true}

                      :expr    [:term :term'*] expr-eval
                      :term'   [#"[+-]" :term]
                      :term    [:factor :factor'*] expr-eval
                      :factor' [#"[*/]" :factor]
                      :factor  #{:integer ["(" :expr ")"]}
                      :integer #"[0-9]+" read-string))

;; Gives 2 as expected:
(stackoverflow "1-2+3")
(stackoverflow "1-2+3" :grammar-actions false :suppress-tags false)
;; Gives -4 as it should:
(stackoverflow "1-(2+3)")
;; Even this beast of mathematical wonder works:
(stackoverflow "1-2/(3-4)+5*6")

;; "As example use of our combinators, consider the following ambiguous grammar from Tomita (1986)."
;; http://cs.uwindsor.ca/~richard/PUBLICATIONS/PADL_08.pdf

;; s ::= np vp | s pp   np ::= noun | det noun | np pp
;; pp ::= prep np       vp ::= verb np
;; det ::= "a" | "the"  noun ::= "i" | "man" | "park" | "bat"
;; verb ::= "saw"       prep ::= "in" | "with"
(def ambiguous (create-parser
                {:capture-string-literals true}

                :s    #{[:np :vp] [:s :pp]}
                :pp   [:prep :np]
                :det  #{"a" "the"}
                :verb "saw"
                :np   #{:noun [:det :noun] [:np :pp]}
                :vp   [:verb :np]
                :noun #{"i" "man" "park" "bat"}
                :prep #{"in" "with"}))

(ambiguous "i saw a man in the park with a bat")
