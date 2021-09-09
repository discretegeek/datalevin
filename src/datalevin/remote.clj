(ns datalevin.remote
  "Proxy for remote stores"
  (:require [datalevin.util :as u]
            [datalevin.constants :as c]
            [datalevin.client :as cl]
            [datalevin.storage :as s]
            [datalevin.lmdb :as l]
            [taoensso.nippy :as nippy]
            [clojure.string :as str])
  (:import [datalevin.client Client]
           [datalevin.storage IStore]
           [datalevin.lmdb ILMDB]
           [java.nio.file Files Paths StandardOpenOption LinkOption]
           [java.nio.file.attribute PosixFilePermissions FileAttribute]
           [java.net URI]))

(defn dtlv-uri?
  "return true if the given string is a Datalevin connection string"
  [s]
  (when s (str/starts-with? s "dtlv://")))

(defn- redact-uri
  [s]
  (if (dtlv-uri? s)
    (str/replace-first s #"(dtlv://.+):(.+)@" "$1:***@")
    s))

;; remote datalog store

(defprotocol IRemoteQuery
  (q [store query inputs]
    "For special case of queries with a single remote store as source"))

(deftype DatalogStore [^String uri ^Client client]
  IStore
  (dir [_] uri)

  (close [_]
    (cl/normal-request client :close nil)
    (cl/disconnect client))

  (closed? [_] (cl/disconnected? client))

  (last-modified [_] (cl/normal-request client :last-modified nil))

  (schema [_] (cl/normal-request client :schema nil))

  (rschema [_] (cl/normal-request client :rschema nil))

  (set-schema [_ new-schema] (cl/normal-request client :set-schema [new-schema]))

  (init-max-eid [_] (cl/normal-request client :init-max-eid nil))

  (swap-attr [this attr f]
    (s/swap-attr this attr f nil nil))
  (swap-attr [this attr f x]
    (s/swap-attr this attr f x nil))
  (swap-attr [_ attr f x y]
    (let [frozen-f (nippy/fast-freeze f)]
      (cl/normal-request client :swap-attr [attr frozen-f x y])))

  (datom-count [_ index] (cl/normal-request client :datom-count [index]))

  (load-datoms [_ datoms]
    (let [{:keys [type message]}
          (if (< (count datoms) c/+wire-datom-batch-size+)
            (cl/request client {:type :load-datoms :mode :request
                                :args [datoms]})
            (cl/copy-in client {:type :load-datoms :mode :copy-in}
                        datoms c/+wire-datom-batch-size+))]
      (when (= type :error-response)
        (u/raise "Error loading datoms to server:" message {:uri uri}))))

  (fetch [_ datom] (cl/normal-request client :fetch [datom]))

  (populated? [_ index low-datom high-datom]
    (cl/normal-request client :populated? [index low-datom high-datom]))

  (size [_ index low-datom high-datom]
    (cl/normal-request client :size [index low-datom high-datom]))

  (head [_ index low-datom high-datom]
    (cl/normal-request client :head [index low-datom high-datom]))

  (tail [_ index high-datom low-datom]
    (cl/normal-request client :tail [index high-datom low-datom]))

  (slice [_ index low-datom high-datom]
    (cl/normal-request client :slice [index low-datom high-datom]))

  (rslice [_ index high-datom low-datom]
    (cl/normal-request client :rslice [index high-datom low-datom]))

  (size-filter [_ index pred low-datom high-datom]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :size-filter
                         [index frozen-pred low-datom high-datom])))

  (head-filter [_ index pred low-datom high-datom]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :head-filter
                         [index frozen-pred low-datom high-datom])))

  (tail-filter [_ index pred high-datom low-datom]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :tail-filter
                         [index frozen-pred high-datom low-datom])))

  (slice-filter [_ index pred low-datom high-datom]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :slice-filter
                         [index frozen-pred low-datom high-datom])))

  (rslice-filter [_ index pred high-datom low-datom]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :rslice-filter
                         [index frozen-pred high-datom low-datom])))

  IRemoteQuery
  (q [_ query inputs]
    (cl/normal-request client :q [query inputs])))

(defn open
  "Open a remote Datalog store"
  ([uri-str]
   (open uri-str nil))
  ([uri-str schema]
   (let [uri (URI. uri-str)]
     (assert (cl/parse-db uri) "URI should contain a database name")
     (->DatalogStore (redact-uri uri-str)
                     (cl/new-client uri-str schema)))))

;; remote kv store

(deftype KVStore [^String uri ^Client client]
  ILMDB

  (dir [_] uri)

  (close-kv [_]
    (cl/normal-request client :close-kv nil)
    (cl/disconnect client))

  (closed-kv? [_] (cl/disconnected? client))

  (open-dbi [db dbi-name]
    (l/open-dbi db dbi-name c/+max-key-size+ c/+default-val-size+))
  (open-dbi [db dbi-name key-size]
    (l/open-dbi db dbi-name key-size c/+default-val-size+))
  (open-dbi [_ dbi-name key-size val-size]
    (cl/normal-request client :open-dbi [dbi-name key-size val-size]))

  (clear-dbi [db dbi-name] (cl/normal-request client :clear-dbi [dbi-name]))

  (drop-dbi [db dbi-name] (cl/normal-request client :drop-dbi [dbi-name]))

  (list-dbis [db] (cl/normal-request client :list-dbis nil))

  (copy [db dest] (l/copy db dest false))
  (copy [db dest compact?]
    (let [bs   (->> (cl/normal-request client :copy [compact?])
                    (apply str)
                    u/decode-base64)
          dir  (Paths/get dest (into-array String []))
          file (Paths/get (str dest u/+separator+ "data.mdb")
                          (into-array String []))]
      (when-not (Files/exists dir (into-array LinkOption []))
        (u/create-dirs dest))
      (Files/write file ^bytes bs (into-array StandardOpenOption []))))

  (stat [db] (l/stat db nil))
  (stat [db dbi-name] (cl/normal-request client :stat [dbi-name]))

  (entries [db dbi-name] (cl/normal-request client :entries [dbi-name]))

  (transact-kv [db txs]
    (let [{:keys [type message]}
          (if (< (count txs) c/+wire-datom-batch-size+)
            (cl/request client {:type :transact-kv :mode :request
                                :args [txs]})
            (cl/copy-in client {:type :transact-kv :mode :copy-in}
                        txs c/+wire-datom-batch-size+))]
      (when (= type :error-response)
        (u/raise "Error transacting kv to server:" message {:uri uri}))))

  (get-value [db dbi-name k]
    (l/get-value db dbi-name k :data :data true))
  (get-value [db dbi-name k k-type]
    (l/get-value db dbi-name k k-type :data true))
  (get-value [db dbi-name k k-type v-type]
    (l/get-value db dbi-name k k-type v-type true))
  (get-value [db dbi-name k k-type v-type ignore-key?]
    (cl/normal-request client :get-value [dbi-name k k-type v-type ignore-key?]))

  (get-first [db dbi-name k-range]
    (l/get-first db dbi-name k-range :data :data false))
  (get-first [db dbi-name k-range k-type]
    (l/get-first db dbi-name k-range k-type :data false))
  (get-first [db dbi-name k-range k-type v-type]
    (l/get-first db dbi-name k-range k-type v-type false))
  (get-first [db dbi-name k-range k-type v-type ignore-key?]
    (cl/normal-request client :get-first [dbi-name k-range k-type v-type ignore-key?]))

  (get-range [db dbi-name k-range]
    (l/get-range db dbi-name k-range :data :data false))
  (get-range [db dbi-name k-range k-type]
    (l/get-range db dbi-name k-range k-type :data false))
  (get-range [db dbi-name k-range k-type v-type]
    (l/get-range db dbi-name k-range k-type v-type false))
  (get-range [db dbi-name k-range k-type v-type ignore-key?]
    (cl/normal-request client :get-range [dbi-name k-range k-type v-type ignore-key?]))

  (range-count [db dbi-name k-range]
    (l/range-count db dbi-name k-range :data))
  (range-count [db dbi-name k-range k-type]
    (cl/normal-request client :range-count [dbi-name k-range k-type]))

  (get-some [db dbi-name pred k-range]
    (l/get-some db dbi-name pred k-range :data :data false))
  (get-some [db dbi-name pred k-range k-type]
    (l/get-some db dbi-name pred k-range k-type :data false))
  (get-some [db dbi-name pred k-range k-type v-type]
    (l/get-some db dbi-name pred k-range k-type v-type false))
  (get-some [db dbi-name pred k-range k-type v-type ignore-key?]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :get-some
                         [dbi-name frozen-pred k-range k-type v-type
                          ignore-key?])))

  (range-filter [db dbi-name pred k-range]
    (l/range-filter db dbi-name pred k-range :data :data false))
  (range-filter [db dbi-name pred k-range k-type]
    (l/range-filter db dbi-name pred k-range k-type :data false))
  (range-filter [db dbi-name pred k-range k-type v-type]
    (l/range-filter db dbi-name pred k-range k-type v-type false))
  (range-filter [db dbi-name pred k-range k-type v-type ignore-key?]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :range-filter
                         [dbi-name frozen-pred k-range k-type v-type
                          ignore-key?])))

  (range-filter-count [db dbi-name pred k-range]
    (l/range-filter-count db dbi-name pred k-range :data))
  (range-filter-count [db dbi-name pred k-range k-type]
    (let [frozen-pred (nippy/fast-freeze pred)]
      (cl/normal-request client :range-filter-count
                         [dbi-name frozen-pred k-range k-type]))))

(defn open-kv
  "Open a remote kv store."
  [uri-str]
  (let [uri     (URI. uri-str)
        uri-str (str uri-str
                     (if (cl/parse-query uri) "&" "?")
                     "store=" c/db-store-kv)]
    (assert (cl/parse-db uri) "URI should contain a database name")
    (->KVStore (redact-uri uri-str) (cl/new-client uri-str))))