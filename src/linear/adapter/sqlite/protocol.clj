(ns linear.adapter.sqlite.protocol
  (:refer-clojure :exclude [read sync])
  (:require
   [linear.spec :refer [spec-for]]))

(defmethod spec-for ::offset [_]
  [:and :int [:>= 0]])

(defmethod spec-for ::length [_]
  [:and :int [:>= 0]])

(defmethod spec-for ::path [_]
  [:and :string [:fn seq]])

(defmethod spec-for ::bytes [_]
  [:fn bytes?])

(defprotocol FileSystem
  (-open [filesystem request])
  (-delete [filesystem request])
  (-access [filesystem request])
  (-full-path [filesystem request]))

(defprotocol File
  (-close [file])
  (-read [file offset length])
  (-write [file offset bytes])
  (-truncate [file length])
  (-sync [file])
  (-size [file]))

(defmethod spec-for ::filesystem [_]
  [:fn #(satisfies? FileSystem %)])

(defmethod spec-for ::file [_]
  [:fn #(satisfies? File %)])

(defn open
  {:malli/schema [:->
                  ::filesystem
                  [:map
                   [:path ::path]
                   [:requested-mode [:enum :read-only :read-write]]
                   [:kind [:enum
                           :main-db
                           :temp-db
                           :transient-db
                           :main-journal
                           :temp-journal
                           :subjournal
                           :super-journal
                           :wal]]
                   [:options [:set [:enum
                                    :create
                                    :delete-on-close
                                    :exclusive
                                    :auto-proxy
                                    :uri
                                    :memory
                                    :no-mutex
                                    :full-mutex
                                    :shared-cache
                                    :private-cache
                                    :no-follow
                                    :extended-result-codes]]]]
                  [:map
                   [:file ::file]
                   [:mode [:enum :read-only :read-write]]]]}
  [filesystem {:as request}]
  (-open filesystem request))

(defn delete
  {:malli/schema [:->
                  ::filesystem
                  [:map [:path ::path]]
                  :nil]}
  [filesystem {:as request}]
  (-delete filesystem request))

(defn access
  {:malli/schema [:->
                  ::filesystem
                  [:map
                   [:path ::path]
                   [:mode [:enum :exists :read :read-write]]]
                  :boolean]}
  [filesystem {:as request}]
  (-access filesystem request))

(defn full-path
  {:malli/schema [:->
                  ::filesystem
                  [:map [:path ::path]]
                  ::path]}
  [filesystem {:as request}]
  (-full-path filesystem request))

(defn close
  {:malli/schema [:-> ::file :nil]}
  [file]
  (-close file))

(defn read
  {:malli/schema [:->
                  ::file
                  ::offset
                  ::length
                  [:map
                   [:bytes ::bytes]
                   [:short-read? :boolean]]]}
  [file offset length]
  (-read file offset length))

(defn write
  {:malli/schema [:-> ::file ::offset ::bytes :nil]}
  [file offset bytes]
  (-write file offset bytes))

(defn truncate
  {:malli/schema [:-> ::file ::length :nil]}
  [file length]
  (-truncate file length))

(defn sync
  {:malli/schema [:-> ::file :nil]}
  [file]
  (-sync file))

(defn size
  {:malli/schema [:-> ::file ::length]}
  [file]
  (-size file))
