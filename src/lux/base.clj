(ns lux.base
  (:require (clojure [template :refer [do-template]])
            [clojure.core.match :as M :refer [matchv]]
            clojure.core.match.array))

;; [Exports]
(def +name-separator+ ";")

(defn T [& elems]
  (to-array elems))

(defn V [tag value]
  (to-array [tag value]))

(defn R [& kvs]
  (to-array kvs))

(defn get$ [slot record]
  (let [size (alength record)]
    (loop [idx 0]
      (if (< idx size)
        (if (= slot (aget record idx))
          (aget record (+ 1 idx))
          (recur (+ 2 idx)))
        (assert false)))))

(defn set$ [slot value record]
  (let [record (aclone record)
        size (alength record)]
    (loop [idx 0]
      (if (< idx size)
        (if (= slot (aget record idx))
          (doto record
            (aset (+ 1 idx) value))
          (recur (+ 2 idx)))
        (assert false)))))

(defmacro update$ [slot f record]
  `(let [record# ~record]
     (set$ ~slot (~f (get$ ~slot record#))
           record#)))

(defn fail* [message]
  (V "lux;Left" message))

(defn return* [state value]
  (V "lux;Right" (T state value)))

(defmacro |let [bindings body]
  (reduce (fn [inner [left right]]
            `(matchv ::M/objects [~right]
               [~left]
               ~inner))
          body
          (reverse (partition 2 bindings))))

(defmacro |list [& elems]
  (reduce (fn [tail head]
            `(V "lux;Cons" (T ~head ~tail)))
          `(V "lux;Nil" nil)
          (reverse elems)))

(defmacro |table [& elems]
  (reduce (fn [table [k v]]
            `(|put ~k ~v ~table))
          `(|list)
          (reverse (partition 2 elems))))

(defn |get [slot table]
  ;; (prn '|get slot (aget table 0))
  (matchv ::M/objects [table]
    [["lux;Nil" _]]
    nil
    
    [["lux;Cons" [[k v] table*]]]
    (if (= k slot)
      v
      (|get slot table*))))

(defn |put [slot value table]
  (matchv ::M/objects [table]
    [["lux;Nil" _]]
    (V "lux;Cons" (T (T slot value) (V "lux;Nil" nil)))
    
    [["lux;Cons" [[k v] table*]]]
    (if (= k slot)
      (V "lux;Cons" (T (T slot value) table*))
      (V "lux;Cons" (T (T k v) (|put slot value table*))))))

(defn |merge [table1 table2]
  ;; (prn '|merge (aget table1 0) (aget table2 0))
  (matchv ::M/objects [table2]
    [["lux;Nil" _]]
    table1

    [["lux;Cons" [[k v] table2*]]]
    (|merge (|put k v table1) table2*)))

(defn |update [k f table]
  (matchv ::M/objects [table]
    [["lux;Nil" _]]
    table

    [["lux;Cons" [[k* v] table*]]]
    (if (= k k*)
      (V "lux;Cons" (T (T k (f v)) table*))
      (|update k f table*))))

(defn |head [xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (assert false)

    [["lux;Cons" [x _]]]
    x))

(defn |tail [xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (assert false)

    [["lux;Cons" [_ xs*]]]
    xs*))

;; [Resources/Monads]
(defn fail [message]
  (fn [_]
    (V "lux;Left" message)))

(defn return [value]
  (fn [state]
    (V "lux;Right" (T state value))))

(defn bind [m-value step]
  ;; (prn 'bind m-value step)
  (fn [state]
    (let [inputs (m-value state)]
      ;; (prn 'bind/inputs (aget inputs 0))
      (matchv ::M/objects [inputs]
        [["lux;Right" [?state ?datum]]]
        ((step ?datum) ?state)
        
        [_]
        inputs))))

(defmacro exec [steps return]
  (assert (not= 0 (count steps)) "The steps can't be empty!")
  (assert (= 0 (rem (count steps) 2)) "The number of steps must be even!")
  (reduce (fn [inner [label computation]]
            (case label
              :let `(let ~computation ~inner)
              ;; else
              `(bind ~computation (fn [~label] ~inner))))
          return
          (reverse (partition 2 steps))))

;; [Resources/Combinators]
(defn try% [monad]
  (fn [state]
    (matchv ::M/objects [(monad state)]
      [["lux;Right" [?state ?datum]]]
      (return* ?state ?datum)
      
      [_]
      (return* state nil))))

(defn |cons [head tail]
  (V "lux;Cons" (T head tail)))

(defn |++ [xs ys]
  ;; (prn '|++ (and xs (aget xs 0)) (and ys (aget ys 0)))
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    ys

    [["lux;Cons" [x xs*]]]
    (V "lux;Cons" (T x (|++ xs* ys)))))

(defn |map [f xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    xs

    [["lux;Cons" [x xs*]]]
    (V "lux;Cons" (T (f x) (|map f xs*)))))

(defn |filter [p xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    xs

    [["lux;Cons" [x xs*]]]
    (if (p x)
      (V "lux;Cons" (T x (|filter p xs*)))
      (|filter p xs*))))

(defn flat-map [f xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    xs

    [["lux;Cons" [x xs*]]]
    (|++ (f x) (flat-map f xs*))))

(defn |split-with [p xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (T xs xs)

    [["lux;Cons" [x xs*]]]
    (if (p x)
      (|let [[pre post] (|split-with p xs*)]
        (T (|cons x pre) post))
      (T (V "lux;Nil" nil) xs))))

(defn |contains? [k table]
  (matchv ::M/objects [table]
    [["lux;Nil" _]]
    false

    [["lux;Cons" [[k* _] table*]]]
    (or (= k k*)
        (|contains? k table*))))

(defn fold [f init xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    init

    [["lux;Cons" [x xs*]]]
    (fold f (f init x) xs*)))

(defn fold% [f init xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (return init)

    [["lux;Cons" [x xs*]]]
    (exec [init* (f init x)]
      (fold% f init* xs*))))

(defn folds [f init xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (|list init)

    [["lux;Cons" [x xs*]]]
    (|cons init (folds f (f init x) xs*))))

(defn |length [xs]
  ;; (prn '|length (aget xs 0))
  (fold (fn [acc _] (inc acc)) 0 xs))

(let [|range* (fn |range* [from to]
                (if (< from to)
                  (V "lux;Cons" (T from (|range* (inc from) to)))
                  (V "lux;Nil" nil)))]
  (defn |range [n]
    (|range* 0 n)))

(defn |first [pair]
  (|let [[_1 _2] pair]
    _1))

(defn |second [pair]
  (|let [[_1 _2] pair]
    _2))

(defn zip2 [xs ys]
  (matchv ::M/objects [xs ys]
    [["lux;Cons" [x xs*]] ["lux;Cons" [y ys*]]]
    (V "lux;Cons" (T (T x y) (zip2 xs* ys*)))

    [_ _]
    (V "lux;Nil" nil)))

(defn |keys [plist]
  (matchv ::M/objects [plist]
    [["lux;Nil" _]]
    (|list)
    
    [["lux;Cons" [[k v] plist*]]]
    (|cons k (|keys plist*))))

(defn |interpose [sep xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    xs

    [["lux;Cons" [_ ["lux;Nil" _]]]]
    xs
    
    [["lux;Cons" [x xs*]]]
    (V "lux;Cons" (T x (V "lux;Cons" (T sep (|interpose sep xs*)))))))

(do-template [<name> <joiner>]
  (defn <name> [f xs]
    (matchv ::M/objects [xs]
      [["lux;Nil" _]]
      (return xs)

      [["lux;Cons" [x xs*]]]
      (exec [y (f x)
             ys (<name> f xs*)]
        (return (<joiner> y ys)))))

  map%      |cons
  flat-map% |++)

(defn |as-pairs [xs]
  (matchv ::M/objects [xs]
    [["lux;Cons" [x ["lux;Cons" [y xs*]]]]]
    (V "lux;Cons" (T (T x y) (|as-pairs xs*)))

    [_]
    (V "lux;Nil" nil)))

(defn |reverse [xs]
  (fold (fn [tail head]
          (|cons head tail))
        (|list)
        xs))

(defn show-table [table]
  (prn 'show-table (aget table 0))
  (str "{{"
       (->> table
            (|map (fn [kv] (|let [[k v] kv] (str k " = ???"))))
            (|interpose " ")
            (fold str ""))
       "}}"))

(defn if% [text-m then-m else-m]
  (exec [? text-m]
    (if ?
      then-m
      else-m)))

(defn apply% [monad call-state]
  (fn [state]
    ;; (prn 'apply-m monad call-state)
    (let [output (monad call-state)]
      ;; (prn 'apply-m/output output)
      (matchv ::M/objects [output]
        [["lux;Right" [?state ?datum]]]
        (return* state ?datum)
        
        [_]
        output))))

(defn assert! [test message]
  (if test
    (return nil)
    (fail message)))

(defn comp% [f-m g-m]
  (exec [temp g-m]
    (f-m temp)))

(defn pass [m-value]
  (fn [state]
    m-value))

(def get-state
  (fn [state]
    (return* state state)))

(defn sequence% [m-values]
  (matchv ::M/objects [m-values]
    [["lux;Cons" [head tail]]]
    (exec [_ head]
      (sequence% tail))

    [_]
    (return nil)))

(defn repeat% [monad]
  (fn [state]
    (matchv ::M/objects [(monad state)]
      [["lux;Right" [?state ?head]]]
      (do ;; (prn 'repeat-m/?state ?state)
          (matchv ::M/objects [((repeat% monad) ?state)]
            [["lux;Right" [?state* ?tail]]]
            (do ;; (prn 'repeat-m/?state* ?state*)
                (return* ?state* (|cons ?head ?tail)))))
      
      [["lux;Left" ?message]]
      (do ;; (println "Failed at last:" ?message)
          (return* state (V "lux;Nil" nil))))))

(def source-consumed?
  (fn [state]
    (matchv ::M/objects [(get$ "lux;source" state)]
      [["lux;None" _]]
      (fail* "No source code.")

      [["lux;Some" ["lux;Nil" _]]]
      (return* state true)

      [["lux;Some" _]]
      (return* state false))))

(defn try-all% [monads]
  (matchv ::M/objects [monads]
    [["lux;Nil" _]]
    (fail "There are no alternatives to try!")

    [["lux;Cons" [m monads*]]]
    (fn [state]
      (let [output (m state)]
        (matchv ::M/objects [output monads*]
          [["lux;Right" _] _]
          output

          [_ ["lux;Nil" _]]
          output
          
          [_ _]
          ((try-all% monads*) state)
          )))
    ))

(defn exhaust% [step]
  (try-all% (|list (exec [output-h step
                          output-t (exhaust% step)]
                     (return (|cons output-h output-t)))
                   (return (|list))
                   (exec [? source-consumed?]
                     (if ?
                       (return (|list))
                       (exhaust% step))))))

(defn ^:private normalize-char [char]
  (case char
    \* "_ASTER_"
    \+ "_PLUS_"
    \- "_DASH_"
    \/ "_SLASH_"
    \\ "_BSLASH_"
    \_ "_UNDERS_"
    \% "_PERCENT_"
    \$ "_DOLLAR_"
    \' "_QUOTE_"
    \` "_BQUOTE_"
    \@ "_AT_"
    \^ "_CARET_"
    \& "_AMPERS_"
    \= "_EQ_"
    \! "_BANG_"
    \? "_QM_"
    \: "_COLON_"
    \. "_PERIOD_"
    \, "_COMMA_"
    \< "_LT_"
    \> "_GT_"
    \~ "_TILDE_"
    ;; default
    char))

(defn normalize-ident [ident]
  (reduce str "" (map normalize-char ident)))

(def loader
  (fn [state]
    (return* state (get$ "lux;loader" state))))

(def +init-bindings+
  (R "lux;counter" 0
     "lux;mappings" (|table)))

(defn env [name]
  (R "lux;name" name
     "lux;inner-closures" 0
     "lux;locals"  +init-bindings+
     "lux;closure" +init-bindings+))

(defn init-state [_]
  (R "lux;source"         (V "lux;None" nil)
     "lux;modules"        (|table)
     "lux;module-aliases" (|table)
     "lux;global-env"     (V "lux;None" nil)
     "lux;local-envs"     (|list)
     "lux;types"          +init-bindings+
     "lux;writer"         (V "lux;None" nil)
     "lux;loader"         (-> (java.io.File. "./output/") .toURL vector into-array java.net.URLClassLoader.)
     "lux;eval-ctor"      0))

(defn from-some [some]
  (matchv ::M/objects [some]
    [["lux;Some" datum]]
    datum

    [_]
    (assert false)))

(defn show-state [state]
  (let [source (get$ "lux;source" state)
        modules (get$ "lux;modules" state)
        global-env (get$ "lux;global-env" state)
        local-envs (get$ "lux;local-envs" state)
        types (get$ "lux;types" state)
        writer (get$ "lux;writer" state)
        loader (get$ "lux;loader" state)
        eval-ctor (get$ "lux;eval-ctor" state)]
    (str "{"
         (->> (for [slot ["lux;source", "lux;modules", "lux;global-env", "lux;local-envs", "lux;types", "lux;writer", "lux;loader", "lux;eval-ctor"]
                    :let [value (get$ slot state)]]
                (str "#" slot " " (case slot
                                    "lux;source" "???"
                                    "lux;modules" "???"
                                    "lux;global-env" (->> value from-some (get$ "lux;locals") (get$ "lux;mappings") show-table)
                                    "lux;local-envs" (str "("
                                                          (->> value
                                                               (|map #(->> % (get$ "lux;locals") (get$ "lux;mappings") show-table))
                                                               (|interpose " ")
                                                               (fold str ""))
                                                          ")")
                                    "lux;types" "???"
                                    "lux;writer" "???"
                                    "lux;loader" "???"
                                    "lux;eval-ctor" value)))
              (interpose " ")
              (reduce str ""))
         "}")))

(def get-eval-ctor
  (fn [state]
    (return* (update$ "lux;eval-ctor" inc state) (get$ "lux;eval-ctor" state))))

(def get-writer
  (fn [state]
    (let [writer* (get$ "lux;writer" state)]
      ;; (prn 'get-writer (class writer*))
      ;; (prn 'get-writer (aget writer* 0))
      (matchv ::M/objects [writer*]
        [["lux;Some" datum]]
        (return* state datum)

        [_]
        (fail* "Writer hasn't been set.")))))

(def get-top-local-env
  (fn [state]
    (try (let [top (|head (get$ "lux;local-envs" state))]
           (return* state top))
      (catch Throwable _
        (fail "No local environment.")))))

(def get-current-module-env
  (fn [state]
    (let [global-env* (get$ "lux;global-env" state)]
      ;; (prn 'get-current-module-env (aget global-env* 0))
      (matchv ::M/objects [global-env*]
        [["lux;Some" datum]]
        (return* state datum)

        [_]
        (fail* "Module hasn't been set.")))))

(defn ->seq [xs]
  (matchv ::M/objects [xs]
    [["lux;Nil" _]]
    (list)

    [["lux;Cons" [x xs*]]]
    (cons x (->seq xs*))))

(defn ->list [seq]
  (if (empty? seq)
    (|list)
    (|cons (first seq) (->list (rest seq)))))

(defn |repeat [n x]
  (if (> n 0)
    (|cons x (|repeat (dec n) x))
    (|list)))

(def get-module-name
  (exec [module get-current-module-env]
    (return (get$ "lux;name" module))))

(defn ^:private with-scope [name body]
  (fn [state]
    (let [output (body (update$ "lux;local-envs" #(|cons (env name) %) state))]
      (matchv ::M/objects [output]
        [["lux;Right" [state* datum]]]
        (return* (update$ "lux;local-envs" |tail state*) datum)
        
        [_]
        output))))

(defn with-closure [body]
  (exec [closure-info (try-all% (|list (exec [top get-top-local-env]
                                         (return (T true (->> top (get$ "lux;inner-closures") str))))
                                       (exec [global get-current-module-env]
                                         (return (T false (->> global (get$ "lux;inner-closures") str))))))]
    (matchv ::M/objects [closure-info]
      [[local? closure-name]]
      (fn [state]
        (let [body* (with-scope closure-name
                      body)]
          (body* (if local?
                   (update$ "lux;local-envs" #(|cons (update$ "lux;inner-closures" inc (|head %))
                                                 (|tail %))
                            state)
                   (update$ "lux;global-env" #(matchv ::M/objects [%]
                                            [["lux;Some" global-env]]
                                            (V "lux;Some" (update$ "lux;inner-closures" inc global-env))

                                            [_]
                                            %)
                            state)))))
      )))

(def get-scope-name
  (exec [module-name get-module-name]
    (fn [state]
      (return* state (->> state (get$ "lux;local-envs") (|map #(get$ "lux;name" %)) |reverse (|cons module-name))))))

(defn with-writer [writer body]
  (fn [state]
    (let [output (body (set$ "lux;writer" (V "lux;Some" writer) state))]
      (matchv ::M/objects [output]
        [["lux;Right" [?state ?value]]]
        (return* (set$ "lux;writer" (get$ "lux;writer" state) ?state) ?value)

        [_]
        output))))

(defn run-state [monad state]
  (monad state))

(defn show-ast [ast]
  ;; (prn 'show-ast (aget ast 0))
  (matchv ::M/objects [ast]
    [["lux;Bool" ?value]]
    (pr-str ?value)

    [["lux;Int" ?value]]
    (pr-str ?value)

    [["lux;Real" ?value]]
    (pr-str ?value)

    [["lux;Char" ?value]]
    (pr-str ?value)

    [["lux;Text" ?value]]
    (str "\"" ?value "\"")

    [["lux;Tag" [?module ?tag]]]
    (str "#" ?module ";" ?tag)

    [["lux;Symbol" [?module ?ident]]]
    (str ?module ";" ?ident)

    [["lux;Tuple" ?elems]]
    (str "[" (->> ?elems (|map show-ast) (|interpose " ") (fold str "")) "]")

    [["lux;Form" ?elems]]
    (str "(" (->> ?elems (|map show-ast) (|interpose " ") (fold str "")) ")")
    ))