(ns datalevin.search-test
  (:require
   [datalevin.search :as sut]
   [datalevin.search-utils :as su]
   [datalevin.lmdb :as l]
   [datalevin.interpret :as i]
   [datalevin.core :as d]
   [datalevin.constants :as c]
   [datalevin.analyzer :as a]
   [datalevin.sparselist :as sl]
   [datalevin.util :as u]
   [datalevin.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as s]
   [jsonista.core :as json]
   [datalevin.test.core :as tdc :refer [db-fixture]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.clojure-test :as test]
   [clojure.test.check.properties :as prop]
   [clojure.test :refer [deftest testing are is use-fixtures]])
  (:import
   [java.util UUID]
   [datalevin.sparselist SparseIntArrayList]
   [datalevin.search SearchEngine IndexWriter]))

(use-fixtures :each db-fixture)

(deftest english-analyzer-test
  (let [s1 "This is a Datalevin-Analyzers test"
        s2 "This is a Datalevin-Analyzers test. "]
    (is (= (a/en-analyzer s1)
           (a/en-analyzer s2)
           [["datalevin-analyzers" 3 10] ["test" 4 30]]))
    (is (= (subs s1 10 (+ 10 (.length "datalevin-analyzers")))
           "Datalevin-Analyzers" ))))

(deftest parse-query-test
  (are [query result] (= (sut/parse-query* a/en-analyzer query) result)
    " "                                    nil
    "red"                                  "red"
    " red "                                "red"
    "red fox"                              [:or "red" "fox"]
    [:or " " "red"]                        [:or "red"]
    [:or "red" [:not "fox"]]               [:or "red" [:not "fox"]]
    [:or "red fox back" " fox "]           [:or [:or "red" "fox" "back"] "fox"]
    [:or "fox" "red" [:and "black" "cat"]] [:or "fox" "red" [:and "black" "cat"]])

  (are [query]
      (thrown-with-msg? Exception #"Invalid search query"
                        (sut/parse-query* a/en-analyzer query))
    []
    [:or]
    [:not]
    ["book"]
    ["book" "red"]
    [:none "book"]))

(deftest required-terms-test
  (are [expr result] (= result (:req (sut/required-terms {:query expr})))
    [:or "a" "b"]  #{"a" "b"}
    [:and "a" "b"] #{"a" "b"}
    [:not "a"]     #{}

    [:or "fox" "red" [:and "black" "sheep"] [:not "yellow"]]
    #{"fox" "red" "black" "sheep"}

    [:not [:or "b" "c"]]                           #{}
    [:and "a" [:or "b" "c"] [:not [:and "d" "e"]]] #{"a" "b" "c"}))

(defn- add-docs
  [f engine]
  (f engine :doc0 "")
  (f engine :doc1 "The quick red fox jumped over the lazy red dogs.")
  (f engine :doc2 "Mary had a little lamb whose fleece was red as fire.")
  (f engine :doc3 "Moby Dick is a story of a whale and a man obsessed.")
  (f engine :doc4 "The robber wore a red fleece jacket and a baseball cap.")
  (f engine :doc5
     "The English Springer Spaniel is the best of all red dogs I know."))

(deftest default-analyzer-test
  (let [dir    (u/tmp-dir (str "test-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir)
        engine (sut/new-search-engine lmdb)]
    (add-docs sut/add-doc engine)
    (is (= [:doc1 :doc5] (sut/search engine "dogs")))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest blank-analyzer-test
  (let [blank-analyzer (fn [^String text]
                         (map-indexed (fn [i ^String t]
                                        [t i (.indexOf text t)])
                                      (s/split text #"\s")))
        dir            (u/tmp-dir (str "analyzer-" (UUID/randomUUID)))
        lmdb           (l/open-kv dir {:flags
                                       (conj c/default-env-flags :nosync)})
        engine         (sut/new-search-engine
                         lmdb {:analyzer        blank-analyzer
                               :index-position? true})]
    (add-docs sut/add-doc engine)
    (is (= [[:doc1 [["dogs." [43]]]]]
           (sut/search engine "dogs." {:display :offsets})))
    (is (= [:doc5] (sut/search engine "dogs")))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest boolean-expression-test
  (let [dir    (u/tmp-dir (str "test-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir)
        engine (sut/new-search-engine lmdb)]
    (add-docs sut/add-doc engine)

    (are [query result] (= result (set (sut/search engine query)))
      [:and "red" [:not "fleece"]]          #{:doc1 :doc5}
      [:and "red" [:not "nonexistentterm"]] #{:doc1 :doc2 :doc4 :doc5}
      [:and "man" "whale"]                  #{:doc3}
      [:and "red" [:not "dogs"]]            #{:doc2 :doc4}
      [:or "" "whale"]                      #{:doc3}
      [:or "nonexistentterm" "whale"]       #{:doc3}
      [:not "red"]                          #{:doc3}
      [:not [:and "red" "fox"]]             #{:doc2 :doc3 :doc4 :doc5}
      [:not "red fox"]                      #{:doc3}
      "red fox"                             #{:doc1 :doc2 :doc4 :doc5}

      [:or "fox" "red" [:and "black" "sheep"] [:not "yellow"]]
      #{:doc1 :doc2 :doc4 :doc5}

      [:or "fox" "red" [:and "black" "sheep"] [:not "fleece"]]
      #{:doc1 :doc2 :doc4 :doc5}
      )

    (are [query] (empty? (sut/search engine query))
      ""
      "  "
      "nonexistentterm"
      [:and "nonexistentterm" "red"]
      [:and "red" [:not "red"]] ; Conflicting condition
      [:or "" ""]
      )

    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest phrase-test
  (let [dir    (u/tmp-dir (str "test-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir)
        engine (sut/new-search-engine lmdb {:index-position? true})]
    (add-docs sut/add-doc engine)

    (are [query result] (= result (set (sut/search engine query)))
      {:phrase "red dogs"}                               #{:doc1 :doc5}
      [:and "red" [:not {:phrase "red fox"}]]            #{:doc2 :doc4 :doc5}
      [:and {:phrase "red dogs"} {:phrase "red fox"}]    #{:doc1}
      [:or {:phrase "little lamb"} {:phrase "red dogs"}] #{:doc1 :doc2 :doc5}
      [:or {:phrase "little lamb"} {:phrase "none dog"}] #{:doc2}
      [:and {:term "red"} {:phrase "baseball cap"}]      #{:doc4}

      [:or {:phrase "little lamb"} [:not {:phrase "none dog"}]] #{:doc2}

      [:not {:phrase "english springer spaniel"}] #{:doc1 :doc2 :doc3 :doc4}

      [:and {:phrase "red dogs"} [:or "fleece" {:phrase "red fox"}]] #{:doc1}

      [:or {:phrase "fleece jacket"} {:phrase "baseball cap"} "story"] #{:doc3 :doc4}

      [:or [:not [:and {:phrase "fleece jacket"} "robber"]] [:not {:phrase "red dogs"}]]
      #{:doc2 :doc3}
      )

    (are [query] (empty? (sut/search engine query))
      {:phrase "nonexistent phrase"}
      [:and "red" {:phrase "nonexistent term"}]
      [:and "fleece" {:phrase "red dogs"}]
      )

    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest index-test
  (let [dir           (u/tmp-dir (str "index-" (UUID/randomUUID)))
        lmdb          (l/open-kv dir {:flags
                                      (conj c/default-env-flags :nosync)})
        engine        ^SearchEngine (sut/new-search-engine
                                      lmdb {:index-position? true})
        terms-dbi     (.-terms-dbi engine)
        docs-dbi      (.-docs-dbi engine)
        positions-dbi (.-positions-dbi engine)]

    (is (= (sut/doc-count engine) 0))
    (is (not (sut/doc-indexed? engine :doc4)))

    (add-docs sut/add-doc engine)

    (is (sut/doc-indexed? engine :doc4))
    (is (not (sut/doc-indexed? engine :non-existent)))
    (is (not (sut/doc-indexed? engine "non-existent")))

    (is (= (sut/doc-count engine) 5))

    (let [[tid _ ^SparseIntArrayList sl]
          (l/get-value lmdb terms-dbi "red" :string :term-info true)]
      (is (= (l/range-count lmdb terms-dbi [:all] :string) 32))
      (is (= (l/get-value lmdb terms-dbi "red" :string :int) tid))

      (is (sl/contains-index? sl 1))
      (is (sl/contains-index? sl 5))
      (is (= (sl/size sl) 4))
      (is (= (seq (.-indices sl)) [1 2 4 5]))

      (is (= (count
               (first
                 (l/get-value lmdb positions-dbi [1 tid] :int-int :pos-info)))
             (sl/get sl 1)
             2))
      (is (= (count
               (peek
                 (l/get-value lmdb positions-dbi [2 tid] :int-int :pos-info)))
             (sl/get sl 2)
             1))

      (is (nil? (l/get-value lmdb positions-dbi [3 tid] :int-int :pos-info)))
      (is (nil? (sl/get sl 3)))

      (is (= (map #(apply vector %)
                  (l/get-value lmdb positions-dbi [5 tid] :int-int :pos-info))
             [[9] [48]]))
      (is (= (update (l/get-value lmdb docs-dbi :doc1 :data :doc-info true)
                     2 count)
             [1 7 0]))
      (is (= (update (l/get-value lmdb docs-dbi :doc2 :data :doc-info true)
                     2 count)
             [2 8 0]))
      (is (= (update (l/get-value lmdb docs-dbi :doc3 :data :doc-info true)
                     2 count)
             [3 6 0]))
      (is (= (update (l/get-value lmdb docs-dbi :doc4 :data :doc-info true)
                     2 count)
             [4 7 0]))
      (is (= (update (l/get-value lmdb docs-dbi :doc5 :data :doc-info true)
                     2 count)
             [5 9 0]))
      (is (= (l/range-count lmdb docs-dbi [:all]) 5))

      (sut/remove-doc engine :doc1)

      (is (= (sut/doc-count engine) 4))

      (let [[tid _ ^SparseIntArrayList sl]
            (l/get-value lmdb terms-dbi "red" :string :term-info true)]
        (is (not (sut/doc-indexed? engine :doc1)))
        (is (= (l/range-count lmdb docs-dbi [:all]) 4))
        (is (not (sl/contains-index? sl 1)))
        (is (= (sl/size sl) 3))
        (is (nil? (l/get-value lmdb positions-dbi [1 tid] :int-int))))

      (sut/clear-docs engine)
      (is (= (sut/doc-count engine) 0)))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest search-test
  (let [dir    (u/tmp-dir (str "search-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        engine ^SearchEngine (sut/new-search-engine
                               lmdb {:index-position? true})]
    (add-docs sut/add-doc engine)

    (is (= [:doc3 :doc2] (sut/search engine "mary moby")))
    (is (= [:doc1 :doc4 :doc2 :doc5] (sut/search engine "red cat")))
    (is (= (sut/search engine "cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "notaword cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (= (sut/search engine "fleece" {:display :offsets})
           [[:doc4 [["fleece" [22]]]] [:doc2 [["fleece" [29]]]]]))
    (is (= (sut/search engine "red fox" {:display :offsets})
           [[:doc1 [["fox" [14]] ["red" [10 39]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]
            [:doc5 [["red" [48]]]]]))
    (is (= (sut/search engine "red fox" {:doc-filter #(not= % :doc2)})
           [:doc1 :doc4 :doc5]))
    (is (= (sut/search engine "red dogs" {:display :offsets})
           [[:doc1 [["dogs" [43]] ["red" [10 39]]]]
            [:doc5 [["dogs" [52]] ["red" [48]]]]
            [:doc4 [["red" [18]]]]
            [:doc2 [["red" [40]]]]]))
    (is (= (sut/search engine "solar cap" {:display :offsets})
           [[:doc4 [["cap" [51]]]]]))
    (is (empty? (sut/search engine "")))
    (is (empty? (sut/search engine "solar")))
    (is (empty? (sut/search engine "solar wind")))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest search-143-test
  (let [dir           (u/tmp-dir (str "search-143-" (UUID/randomUUID)))
        lmdb          (l/open-kv dir {:flags
                                      (conj c/default-env-flags :nosync)})
        engine        ^SearchEngine (sut/new-search-engine
                                      lmdb {:index-position? true})
        terms-dbi     (.-terms-dbi engine)
        positions-dbi (.-positions-dbi engine)
        docs-dbi      (.-docs-dbi engine)]

    (sut/add-doc engine 1 "a tent")
    (sut/add-doc engine 2 "tent")

    (is (= (sut/doc-count engine) 2))

    (let [[tid mw ^SparseIntArrayList sl]
          (l/get-value lmdb terms-dbi "tent" :string :term-info true)]
      (is (= (l/range-count lmdb terms-dbi [:all] :string) 1))
      (is (= (l/get-value lmdb terms-dbi "tent" :string :int) tid))
      (is (= mw 1.0))

      (is (sl/contains-index? sl 1))
      (is (= (sl/size sl) 2))
      (is (= (seq (.-indices sl)) [1 2]))

      (is (= (count
               (peek
                 (l/get-value lmdb positions-dbi [2 tid] :int-int :pos-info)))
             (sl/get sl 2)
             1))

      (is (nil? (l/get-value lmdb positions-dbi [3 tid] :int-int)))
      (is (nil? (sl/get sl 3)))

      (is (= (map #(apply vector %)
                  (l/get-value lmdb positions-dbi [1 tid] :int-int :pos-info))
             [[1] [2]]))

      (is (= (update (l/get-value lmdb docs-dbi 1 :data :doc-info true)
                     2 set)
             [1 1 #{}]))
      (is (= (l/range-count lmdb docs-dbi [:all]) 2))
      )

    (is (= (sut/search engine "tent") [2 1]))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest multi-domains-test
  (let [dir     (u/tmp-dir (str "search-multi" (UUID/randomUUID)))
        lmdb    (l/open-kv dir {:flags
                                (conj c/default-env-flags :nosync)})
        engine1 ^SearchEngine (sut/new-search-engine lmdb)
        engine2 ^SearchEngine (sut/new-search-engine
                                lmdb {:domain "another"})]
    (sut/add-doc engine1 1 "hello world")
    (sut/add-doc engine1 2 "Mars is a red planet")
    (sut/add-doc engine1 3 "Earth is a blue planet")
    (add-docs sut/add-doc engine2)

    (is (empty? (sut/search engine1 "solar")))
    (is (empty? (sut/search engine2 "solar")))
    (is (= (sut/search engine1 "red") [2]))
    (is (= (sut/search engine2 "red") [:doc1 :doc4 :doc2 :doc5]))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest search-kv-test
  (let [dir    (u/tmp-dir (str "search-kv-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        engine (sut/new-search-engine lmdb {:index-position? true
                                            :include-text?   true})
        texts  {1 "The quick red fox jumped over the lazy red dogs."
                2 "Mary had a little lamb whose fleece was red as fire."
                3 "Moby Dick is a story of a whale and a man obsessed."}]

    (doseq [i [1 2 3]] (sut/add-doc engine i (texts i)))

    (is (not (sut/doc-indexed? engine 0)))
    (is (sut/doc-indexed? engine 1))

    (is (= 3 (sut/doc-count engine)))

    (is (= (sut/search engine "lazy") [1]))
    (is (= (sut/search engine "red" ) [1 2]))
    (is (= (sut/search engine "red" {:display :offsets})
           [[1 [["red" [10 39]]]] [2 [["red" [40]]]]]))
    (is (= (sut/search engine "red" {:display :texts})
           [[1 "The quick red fox jumped over the lazy red dogs."]
            [2 "Mary had a little lamb whose fleece was red as fire."]]))
    (is (= (sut/search engine "red" {:display :texts+offsets})
           [[1  "The quick red fox jumped over the lazy red dogs."
             [["red" [10 39]]]]
            [2  "Mary had a little lamb whose fleece was red as fire."
             [["red" [40]]]]]))

    (testing "update"
      (sut/add-doc engine 1 "The quick fox jumped over the lazy dogs.")
      (is (= (sut/search engine "red" ) [2])))

    (testing "parallel update"
      (dorun (pmap #(sut/add-doc engine %1 %2)
                   [2 1 4]
                   ["May has a little lamb."
                    "The quick red fox jumped over the lazy dogs."
                    "do you know the game truth or dare <p>What's your biggest fear? I want to see if you could tell me the truth :-)</p>"]))
      (is (= (sut/search engine "red" ) [1]))
      (is (= (sut/search engine "truth" ) [4])))

    (testing "duplicated docs"
      (sut/add-doc engine 5
                   "Pricing how much is the price for each juji Whats the price of using juji for classes Hello, what is the price of Juji? <p>You can create me or any of my brothers and sisters FREE. You can also chat with us privately FREE, as long as you want.</p><p><br></p><p>If you wish to make me or any of my sisters or brothers public so we can chat with other folks, I'd be happy to help you find the right price package.</p>")
      (sut/add-doc engine 4
                   "do you know the game truth or dare <p>What's your biggest fear? I want to see if you could tell me the truth :-)")
      (is (= (sut/search engine "truth" ) [4])))

    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest fulltext-fns-test
  (let [analyzer (i/inter-fn
                   [^String text]
                   (map-indexed (fn [i ^String t]
                                  [t i (.indexOf text t)])
                                (s/split text #"\s")))
        dir      (u/tmp-dir (str "fulltext-fns-" (UUID/randomUUID)))
        conn     (d/create-conn
                   dir {:a/id     {:db/valueType :db.type/long
                                   :db/unique    :db.unique/identity}
                        :a/string {:db/valueType :db.type/string
                                   :db/fulltext  true}
                        :b/string {:db/valueType :db.type/string
                                   :db/fulltext  true}}
                   {:auto-entity-time? true
                    :search-opts       {:analyzer analyzer}})
        s        "The quick brown fox jumps over the lazy dog"]
    (d/transact! conn [{:a/id 1 :a/string s :b/string ""}])
    (d/transact! conn [{:a/id 1 :a/string s :b/string "bar"}])
    (is (= (d/q '[:find ?v .
                  :in $ ?q
                  :where [(fulltext $ ?q) [[?e ?a ?v]]]]
                (d/db conn) "brown fox")
           s))
    (is (empty? (d/q '[:find ?v .
                       :in $ ?q
                       :where [(fulltext $ ?q) [[?e ?a ?v]]]]
                     (d/db conn) "")))
    (is (= (d/datom-v
             (first (d/fulltext-datoms (d/db conn) "brown fox")))
           s))
    (d/close conn)
    (u/delete-files dir)))

(defn- rows->maps [csv]
  (let [headers (map keyword (first csv))
        rows    (rest csv)]
    (map #(zipmap headers %) rows)))

(deftest load-csv-test
  (let [dir  (u/tmp-dir (str "load-csv-test-" (UUID/randomUUID)))
        conn (d/create-conn
               dir {:id          {:db/valueType :db.type/string
                                  :db/unique    :db.unique/identity}
                    :description {:db/valueType :db.type/string
                                  :db/fulltext  true}})
        data (rows->maps
               (with-open [reader (io/reader "test/data/data.csv")]
                 (doall (csv/read-csv reader))))]
    (d/transact! conn data)
    (is (= 36 (count (d/fulltext-datoms (d/db conn) "Memorex" {:top 100}))))
    (is (= (d/q '[:find (count ?e) .
                  :in $ ?q
                  :where [(fulltext $ ?q {:top 100}) [[?e _ _]]]]
                (d/db conn) "Memorex")
           36))
    (d/transact! conn [{:id          "42"
                        :description "This is a new description"}])
    (is (= (d/q '[:find ?d .
                  :in $ ?i
                  :where
                  [?e :id ?i]
                  [?e :description ?d]]
                (d/db conn) "42")
           "This is a new description"))
    (d/close conn)
    (u/delete-files dir)))

(deftest load-json-test
  (let [dir  (u/tmp-dir (str "load-json-test-" (UUID/randomUUID)))
        conn (d/create-conn
               dir {:id          {:db/valueType :db.type/long
                                  :db/unique    :db.unique/identity}
                    :description {:db/valueType :db.type/string
                                  :db/fulltext  true}})
        data (json/read-value (slurp "test/data/data.json")
                              json/keyword-keys-object-mapper)]
    (d/transact! conn data)
    (is (= 1 (count (d/fulltext-datoms (d/db conn) "GraphstatsR"))))
    (is (= (update (d/q '[:find [?i ?d]
                          :in $ ?q
                          :where
                          [(fulltext $ ?q) [[?e _ ?d]]]
                          [?e :id ?i]]
                        (d/db conn) "GraphstatsR")
                   1 count)
           [6299 508]))
    (d/transact! conn [{:id          6299
                        :description "This is a new description"}])
    (is (= (d/q '[:find ?d .
                  :in $ ?i
                  :where
                  [?e :id ?i]
                  [?e :description ?d]]
                (d/db conn) 6299)
           "This is a new description"))
    (d/close conn)
    (u/delete-files dir)))

(deftest update-doc-test
  (let [dir       (u/tmp-dir (str "update-doc-test-" (UUID/randomUUID)))
        lmdb      (d/open-kv dir {:flags
                                  (conj c/default-env-flags :nosync)})
        engine    ^SearchEngine (d/new-search-engine lmdb)
        terms-dbi (.-terms-dbi engine)]
    (add-docs d/add-doc engine)
    (let [[_ _ sl]
          (l/get-value lmdb terms-dbi "fox" :string :term-info true)]
      (is (= sl (sl/sparse-arraylist {1 1}))))
    (let [[_ _ sl]
          (l/get-value lmdb terms-dbi "red" :string :term-info true)]
      (is (= sl (sl/sparse-arraylist {1 2 2 1 4 1 5 1}))))
    (is (= {(int 1) :doc1 (int 2) :doc2 (int 3) :doc3 (int 4) :doc4
            (int 5) :doc5}
           (.-docs engine)))
    (is (= 5 (d/doc-count engine)))

    (is (= [:doc1 :doc4 :doc2 :doc5] (d/search engine "red fox")))

    (d/add-doc engine :doc1
               "The quick brown fox jumped over the lazy black dogs.")
    (let [[_ _ sl]
          (l/get-value lmdb terms-dbi "fox" :string :term-info true)]
      (is (= sl (sl/sparse-arraylist {6 1}))))
    (let [[_ _ sl]
          (l/get-value lmdb terms-dbi "red" :string :term-info true)]
      (is (= sl (sl/sparse-arraylist {2 1 4 1 5 1}))))
    (is (= {(int 6) :doc1 (int 2) :doc2 (int 3) :doc3 (int 4) :doc4
            (int 5) :doc5}
           (.-docs engine)))
    (is (= 5 (d/doc-count engine)))
    (is (d/doc-indexed? engine :doc1))

    (is (= [:doc1] (d/search engine "black")))
    (is (= [:doc1] (d/search engine "fox")))
    (is (= [:doc1] (d/search engine "black fox")))
    (is (= [:doc4 :doc2 :doc5] (d/search engine "red")))
    (is (= [:doc1 :doc4 :doc2 :doc5] (d/search engine "red fox")))
    (is (= [:doc1] (d/search engine "brown fox")))
    (is (= [:doc1 :doc5] (d/search engine "brown dogs")))
    (is (= [:doc1 :doc2] (d/search engine "brown lamb")))

    (d/add-doc engine :doc2
               "Mary had a little lamb whose fleece was black as coal.")
    (is (= [:doc2 :doc1] (d/search engine "black")))
    (is (= [:doc1 :doc2] (d/search engine "black fox")))
    (is (= [:doc4 :doc5] (d/search engine "red")))
    (is (= [:doc1 :doc4 :doc5] (d/search engine "red fox")))
    (is (= [:doc2 :doc1] (d/search engine "brown lamb")))

    (d/close-kv lmdb)
    (u/delete-files dir)))

(deftest huge-doc-test
  (let [dir    (u/tmp-dir (str "huge-doc-test-" (UUID/randomUUID)))
        lmdb   (d/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        engine ^SearchEngine (d/new-search-engine
                               lmdb {:index-position? true})]
    (d/add-doc engine "Romeo and Juliet" (slurp "test/data/romeo.txt"))
    (is (= ["Romeo and Juliet"] (d/search engine "romeo")))
    (is (= 4283 (count (.-terms engine))))
    (is (= 299 (count (peek (l/get-value
                              lmdb (.-positions-dbi engine)
                              [1 (l/get-value lmdb (.-terms-dbi engine)
                                              "romeo" :string :int true)]
                              :int-int :pos-info)))))
    (d/close-kv lmdb)
    (u/delete-files dir)))

(deftest index-writer-test
  (let [dir    (u/tmp-dir (str "writer-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        writer ^IndexWriter (sut/search-index-writer
                              lmdb {:index-position? true})]
    (add-docs sut/write writer)
    (sut/commit writer)

    (let [engine (sut/new-search-engine lmdb)]
      (is (= (sut/search engine "cap" {:display :offsets})
             [[:doc4 [["cap" [51]]]]])))
    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest proximity-span-test
  (let [dir      (u/tmp-dir (str "proximity-span-test-" (UUID/randomUUID)))
        lmdb     (d/open-kv dir {:flags
                                 (conj c/default-env-flags :nosync)})
        analyzer (su/create-analyzer
                   {:tokenizer
                    (su/create-regexp-tokenizer #"[\s:\.,'\-()]+")
                    :token-filters [su/lower-case-token-filter
                                    su/unaccent-token-filter]})
        engine   ^SearchEngine (d/new-search-engine
                                 lmdb {:index-position? true
                                       :analyzer        analyzer})]
    (d/add-doc engine "Erosion"
               "Erosion
It took the sea a thousand years,
A thousand years to trace
The Granite features of this cliff,
In crag and scarp and base.

It took the sea an hour one night,
An hour of storm to place
The sculpture of these granite seams,
Upon a woman's face.

- E. J. Pratt (1882 - 1964)")
    (d/add-doc engine "Sea-Gulls"
               "Sea-Guals
For one carved instant as they flew,
The language had no simile—
Silver, crystal, ivory
Were tarnished. Etched upon the horizon blue,
The frieze must go unchallenged, for the lift
And carriage of the wings would stain the drift
Of stars against a tropic indigo
Or dull the parable of snow.
Now settling one by one
Within green hollows or where curled
Crests caught the spectrum from the sun,
A thousand wings are furled.
No clay-born lilies of the world
Could blow as free
As those wild orchids of the sea.
- E. J. Pratt (1882 - 1964)")
    (d/add-doc engine "Horizons"
               "Horizons
You would not come when you were near,
And when the lamp was lit,
And though you always knew I'd hear
Your call and answer it.
Now you would speed across the sea,
To find the door ajar...
The lamp is out, and as for me,
I could not call so far.
- E. J. Pratt (1882 - 1964)
")
    (d/add-doc engine "GPT4-1"
               "sea, cliff, thousand years
Upon the ancient cliffs I stand,
A thousand years and more have passed,
Gazing out upon the sea,
A realm of time forever vast.

Mighty waves with roaring sound,
Caress the shores in rhythmic dance,
As secrets of the deep reveal,
A story born of fate and chance.

The sea, a cradle of life's embrace,
Where countless tales remain untold,
Its surface glistens like a gem,
A treasure chest of dreams and gold.

Beside the cliff, I feel the breeze,
It whispers softly in my ear,
A lullaby from long ago,
Of sailors brave who ventured near.

For centuries, these cliffs have stood,
And watched the tides of time unfold,
Their rugged faces carved by wind,
A testament to days of old.

A thousand years, it seems, have passed,
Yet still, the sea and cliffs remain,
A symbol of eternity,
In this world of joy and pain.

So, as I stand upon this edge,
And marvel at the endless waves,
I find solace in the thought,
That love and hope, like sea and cliffs, will stay.

- ChatGPT (2023 -)")
    (d/add-doc engine "GPT4-2"
               "In style of Erosion
In quiet contemplation, I descended,
To where the ocean meets the land,
And there, upon the rugged shore,
I saw the work of time's own hand.

The waves, they surged with primal force,
Against the rocks they crashed and broke,
In rhythmic dance, they sang a tale,
Of war and love, in whispers spoke.

The rocks, they stood, a bulwark strong,
Defiant 'gainst the ceaseless tide,
With every splash and every spray,
A story etched in their weathered hide.

In cycles past, the land did yield,
To ocean's will and its desire,
Yet, even as it ebbed away,
The shore retained its spirit's fire.

The wind, it howled in cold embrace,
A sculptor's touch, with fingers fine,
It carved its mark upon the shore,
A testament to strength divine.

In moonlit glow, the sea revealed,
A beauty rare, a sight to see,
Its silver sheen, like molten glass,
A mirror to infinity.

The sea, it roared with ancient rage,
Yet, in its heart, a love did dwell,
For every touch, it kissed the shore,
In passion's grip, the two did meld.

And there, I stood, betwixt the two,
A witness to their endless dance,
The sea and shore, in harmony,
A story old, of earth's romance.

Eroded, yet enduring still,
The shore stood tall, a testament,
To time's own hand and nature's will,
A love that's never spent.

- ChatGPT (2023 -)")
    (is (= ["Erosion" "GPT4-1" "Sea-Gulls" "GPT4-2" "Horizons"]
           (sut/search engine "sea thousand years" {:proximity-max-dist 10})))
    (is (= ["Erosion" "GPT4-1" "Sea-Gulls"]
           (sut/search engine "thousand years")))
    (is (= ["Erosion" "GPT4-1" "GPT4-2" "Horizons" "Sea-Gulls"]
           (sut/search engine "sea cliff")))
    (is (= ["Erosion" "Horizons" "Sea-Gulls" ]
           (sut/search engine "e j pratt")))
    (d/close-kv lmdb)
    (u/delete-files dir)))

(deftest proximity-search-test
  (let [dir    (u/tmp-dir (str "proximity-search-test-" (UUID/randomUUID)))
        lmdb   (d/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        engine ^SearchEngine (d/new-search-engine
                               lmdb {:index-position? true})]
    (sut/add-doc engine "bug1"
                 "How would we use this for a membership association.")
    (is (= ["bug1"]
           (sut/search engine "membership association")))
    (is (nil? (sut/search engine "bug")))
    (d/close-kv lmdb)
    (u/delete-files dir)))

(deftest re-index-search-test
  (let [dir    (u/tmp-dir (str "re-index-search-" (UUID/randomUUID)))
        lmdb   (l/open-kv dir {:flags
                               (conj c/default-env-flags :nosync)})
        opts   {:index-position? true
                :include-text?   true}
        engine (sut/new-search-engine lmdb opts)]

    (add-docs sut/add-doc engine)

    (is (empty? (sut/search engine "dog")))

    (let [engine1 (l/re-index
                    engine (merge opts {:analyzer
                                        (su/create-analyzer
                                          {:token-filters
                                           [(su/create-stemming-token-filter
                                              "english")]})}))]
      (is (= [:doc1 :doc5] (sut/search engine1 "dog"))))

    (l/close-kv lmdb)
    (u/delete-files dir)))

(deftest domain-test
  (let [dir      (u/tmp-dir (str "domain-test-" (UUID/randomUUID)))
        analyzer (su/create-analyzer
                   {:token-filters [(su/create-stemming-token-filter
                                      "english")]})
        conn     (d/create-conn
                   dir
                   {:a/id     {:db/valueType :db.type/long
                               :db/unique    :db.unique/identity}
                    :a/string {:db/valueType        :db.type/string
                               :db/fulltext         true
                               :db.fulltext/domains ["da"]}
                    :b/string {:db/valueType           :db.type/string
                               :db/fulltext            true
                               :db.fulltext/autoDomain true
                               :db.fulltext/domains    ["db"]}}
                   {:search-domains {"da" {:analyzer analyzer}
                                     "db" {}}})
        sa       "The quick brown fox jumps over the lazy dogs"
        sb       "Pack my box with five dozen liquor jugs."
        sc       "How vexingly quick daft zebras jump!"
        sd       "Five dogs jump over my fence."]
    (d/transact! conn [{:a/id 1 :a/string sa :b/string sb}])
    (d/transact! conn [{:a/id 2 :a/string sc :b/string sd}])
    (is (thrown-with-msg? Exception #":db.fulltext/autoDomain"
                          (d/q '[:find [?v ...]
                                 :in $ ?q
                                 :where
                                 [(fulltext $ :a/string ?q) [[?e _ ?v]]]]
                               (d/db conn) "jump")))
    (is (= (d/q '[:find [?v ...]
                  :in $ ?q
                  :where
                  [(fulltext $ :b/string ?q) [[?e _ ?v]]]]
                (d/db conn) "jump")
           [sd]))
    (is (= (set (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q {:domains ["da"]}) [[?e _ ?v]]]]
                     (d/db conn) "jump"))
           #{sa sc}))
    (is (= (d/q '[:find [?v ...]
                  :in $ ?q
                  :where
                  [(fulltext $ ?q {:domains ["db"]}) [[?e _ ?v]]]]
                (d/db conn) "jump")
           [sd]))
    (is (= (set (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q {:domains ["da" "db"]}) [[?e _ ?v]]]]
                     (d/db conn) "jump"))
           #{sa sc sd}))
    (is (= (set (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q) [[?e _ ?v]]]]
                     (d/db conn) "jump"))
           #{sa sc sd}))
    (is (= (set (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q) [[?e _ ?v]]]]
                     (d/db conn) "dog"))
           #{sa}))
    (is (= (set (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q) [[?e _ ?v]]]]
                     (d/db conn) "dogs"))
           #{sa sd}))
    (is (empty? (d/q '[:find [?v ...]
                       :in $ ?q
                       :where
                       [(fulltext $ ?q {:domains ["db"]}) [[?e _ ?v]]]]
                     (d/db conn) "dog")))
    (d/close conn)
    (u/delete-files dir)))

;; TODO double compares are not really reliable
;; (def tokens ["b" "c" "d" "e" "f" "g" "h" "i" "j" "k" "l" "m" "n"
;;              "o" "p" "q" "r" "s" "t" "u" "v" "w" "x" "y" "z"])

;; (def doc-num 10)

;; (defn- search-correct
;;   [data query results]
;;   (let [refs (keys data)
;;         docs (vals data)

;;         dfreqs (zipmap refs (map frequencies docs))

;;         norms (zipmap refs (map count (vals dfreqs)))

;;         terms  (s/split query #" ")
;;         qfreqs (frequencies terms)

;;         dfs (reduce (fn [tm term]
;;                       (assoc tm term
;;                              (reduce (fn [dc [_ freqs]]
;;                                        (if (freqs term)
;;                                          (inc ^long dc)
;;                                          dc))
;;                                      0
;;                                      dfreqs)))
;;                     {}
;;                     terms)
;;         wqs (reduce (fn [m [term freq]]
;;                       (assoc m term
;;                              (* ^double (sut/tf* freq)
;;                                 ^double (sut/idf (dfs term) doc-num))))
;;                     {}
;;                     qfreqs)
;;         rc  (count results)]
;;     (println "results =>" results)
;;     (= results
;;        (->> data
;;             (sort-by
;;               (fn [[ref _]]
;;                 (reduce
;;                   +
;;                   (map (fn [term]
;;                          (/ ^double
;;                             (* (double (or (wqs term) 0.0))
;;                                ^double (sut/tf*
;;                                          (or (get-in dfreqs [ref term])
;;                                              0.0)))
;;                             (double (norms ref))))
;;                        terms)))
;;               >)
;;             (#(do (println "data sorted by query =>" %) %))
;;             (take rc)
;;             (map first)))))

;; (test/defspec search-generative-test
;;   1
;;   (prop/for-all
;;     [refs (gen/vector-distinct gen/nat {:num-elements doc-num})
;;      docs (gen/vector-distinct
;;             (gen/vector (gen/elements tokens) 3 10) ;; doc length
;;             {:num-elements doc-num}) ;; num of docs
;;      qs (gen/vector (gen/vector (gen/elements tokens) 1 3) 1)]
;;     (let [dir     (u/tmp-dir (str "search-test-" (UUID/randomUUID)))
;;           lmdb    (d/open-kv dir)
;;           engine  (d/new-search-engine lmdb)
;;           data    (zipmap refs docs)
;;           _       (println "data =>" data)
;;           jf      #(s/join " " %)
;;           txs     (zipmap refs (map jf docs))
;;           queries (map jf qs)
;;           _       (doseq [[k v] txs] (d/add-doc engine k v))
;;           _       (println "queries =>" queries)
;;           ok      (every? #(search-correct data % (d/search engine %))
;;                           queries)
;;           ]
;;       (l/close-kv lmdb)
;;       (u/delete-files dir)
;;       ok)))
