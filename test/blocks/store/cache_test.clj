(ns blocks.store.cache-test
  (:require
    [blocks.core :as block]
    (blocks.store
      [cache :as cache :refer [cache-store]]
      [memory :refer [memory-store]]
      [tests :as tests :refer [test-block-store]])
    [clojure.test :refer :all]
    [com.stuartsierra.component :as component]
    [multihash.core :as multihash]
    [puget.printer :as puget]))


(defn new-cache
  "Helper function to construct a fresh cache store backed by empty memory
  stores."
  [size-limit]
  (cache-store size-limit
    :primary (memory-store)
    :cache (memory-store)))


(deftest store-construction
  (is (thrown? IllegalArgumentException (cache-store nil)))
  (is (thrown? IllegalArgumentException (cache-store 0)))
  (is (thrown? IllegalArgumentException (cache-store 512 :max-block-size "foo")))
  (is (thrown? IllegalArgumentException (cache-store 512 :max-block-size 0)))
  (is (satisfies? block/BlockStore (cache-store 512 :max-block-size 128))))


(deftest store-lifecycle
  (let [store (new-cache 1024)]
    (is (thrown? IllegalStateException
                 (component/start (assoc store :primary nil)))
        "starting cache without primary throws an exception")
    (is (thrown? IllegalStateException
                 (component/start (assoc store :cache nil)))
        "starting cache without cache throws an exception")
    (is (= store (component/start (component/start store)))
        "starting cache store again is a no-op")
    (is (= store (component/stop store))
        "stopping cache store is a no-op")))


(deftest uninitialized-store
  (let [store (new-cache 1024)]
    (testing "no primary store"
      (is (thrown? IllegalStateException (block/list (assoc store :primary nil)))))
    (testing "no cache store"
      (is (thrown? IllegalStateException (block/list (assoc store :cache nil)))))
    (testing "not started"
      (is (thrown? IllegalStateException (block/list store))))))


(deftest extant-cache-contents
  (let [store (new-cache 1024)
        content (tests/populate-blocks! (:cache store) 10 64)
        store' (component/start store)]
    (is (every? #(block/stat (:cache store) %) (keys content))
        "all blocks should still be present in store")
    (is (every? #(contains? (:priorities @(:state store)) %) (keys content))
        "all blocks should have an entry in the priority map")))


(deftest space-reaping
  (let [store (new-cache 1024)
        content (tests/populate-blocks! (:cache store) 32 1024)
        store (component/start store)]
    (is (< 1024 (:total-size @(:state store)))
        "has more than size-limit blocks cached")
    (cache/reap! store 512)
    (is (<= (:total-size @(:state store)) 512)
        "reap cleans up at least the desired free space")))


(deftest ^:integration test-cache-store
  (let [size-limit (* 16 1024)
        primary (memory-store)
        cache (memory-store)
        store (cache-store size-limit
                :max-block-size 1024
                :primary primary
                :cache cache)]
    (test-block-store
      "cache-store" store
      :max-size 4096
      :blocks 50)))
