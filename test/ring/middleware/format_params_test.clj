(ns ring.middleware.format-params-test
  (:use [clojure.test]
        [ring.middleware.format-params])
  (:require [cognitect.transit :as transit]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn stream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def json-echo
  (wrap-params identity {:decoders [:json]}))

;; stolen from ring-json-params to confirm compatibility

(deftest noop-with-other-content-type
  (let [req {:content-type "application/xml"
             :body (stream "<xml></xml>")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= "<xml></xml>" (slurp (:body resp))))
    (is (= {"id" 3} (:params resp)))
    (is (nil? (:json-params resp)))))

(deftest augments-with-json-content-type
  (let [req {:content-type "application/json; charset=UTF-8"
             :body (stream "{\"foo\": \"bar\"}")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= {"id" 3 "foo" "bar"} (:params resp)))
    (is (= {"foo" "bar"} (:body-params resp)))))

(deftest augments-with-vnd-json-content-type
  (let [req {:content-type "application/vnd.foobar+json; charset=UTF-8"
             :body (stream "{\"foo\": \"bar\"}")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= {"id" 3 "foo" "bar"} (:params resp)))
    (is (= {"foo" "bar"} (:body-params resp)))))

(def yaml-echo
  (wrap-params identity {:decoders [:yaml]}))

(deftest augments-with-yaml-content-type
  (let [req {:content-type "application/x-yaml; charset=UTF-8"
             :body (stream "foo: bar")
             :params {"id" 3}}
        resp (yaml-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

(def clojure-echo
  (wrap-params identity {:decoders [:edn]}))

(deftest augments-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo \"bar\"}")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))


(deftest augments-with-clojure-content-prohibit-eval-in-reader
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo #=(java.util.Date.)}")
             :params {"id" 3}}]
    (try
      (let [_ (clojure-echo req)]
        (is false "Eval in reader permits arbitrary code execution."))
      (catch Exception _))))

(deftest no-body-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3} (:params resp)))
    (is (= nil (:body-params resp)))))

(deftest whitespace-body-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "\t  ")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3} (:params resp)))
    (is (= nil (:body-params resp)))))

;;;;;;;;;;;;;
;; Transit ;;
;;;;;;;;;;;;;

(defn stream-transit
  [fmt data]
  (let [out (ByteArrayOutputStream.)
        wrt (transit/writer out fmt)]
    (transit/write wrt data)
    (io/input-stream (.toByteArray out))))

(def transit-json-echo
  (wrap-params identity {:decoders [:transit-json]}))

(deftest augments-with-transit-json-content-type
  (let [req {:content-type "application/transit+json"
             :body (stream-transit :json {:foo "bar"})
             :params {"id" 3}}
             resp (transit-json-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

(def transit-msgpack-echo
  (wrap-params identity {:decoders [:transit-msgpack]}))

(deftest augments-with-transit-msgpack-content-type
  (let [req {:content-type "application/transit+msgpack"
             :body (stream-transit :msgpack {:foo "bar"})
             :params {"id" 3}}
             resp (transit-msgpack-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

;;;;;;;;;;;;;;;;;;;;
;; Restful Params ;;
;;;;;;;;;;;;;;;;;;;;

(def restful-echo
  (wrap-params identity))

(def safe-restful-echo
  (wrap-params identity
               {:handle-error (fn [_ _ _] {:status 500})}))

(deftest test-restful-params-wrapper
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo \"bar\"}")
             :params {"id" 3}}
             resp (restful-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))
    (is (= 500 (get (safe-restful-echo (assoc req :body (stream "{:foo \"bar}"))) :status)))))

(defn stream-iso [s]
  (ByteArrayInputStream. (.getBytes s "ISO-8859-1")))

(deftest test-different-params-charset
  (let [req {:content-type "application/clojure; charset=ISO-8859-1"
             :body (stream-iso "{:fée \"böz\"}")
             :params {"id" 3}}
        resp (restful-echo req)]
    (is (= {"id" 3 :fée "böz"} (:params resp)))
    (is (= {:fée "böz"} (:body-params resp)))))

(deftest test-list-body-request
  (let [req {:content-type "application/json"
             :body (ByteArrayInputStream.
                    (.getBytes "[\"gregor\", \"samsa\"]"))}]
    ((wrap-params
      (fn [{:keys [body-params]}] (is (= ["gregor" "samsa"] body-params)))
      {:decoders [:json]})
     req)))

(deftest test-optional-body
  ((wrap-params
    (fn [request]
      (is (nil? (:body request)))))
   {:body nil}))

(deftest test-custom-handle-error
  (are [format content-type body]
    (let [req {:body (-> body .getBytes ByteArrayInputStream.)
               :content-type content-type}
          resp ((wrap-params identity
                                     {:decoders [format]
                                      :handle-error (constantly {:status 999})})
                req)]
      (= 999 (:status resp)))
    :json "application/json" "{:a 1}"
    :edn "application/edn" "{\"a\": 1}"))

;;
;; Transit options
;;

(defrecord Point [x y])

(def readers
  {"Point" (transit/read-handler (fn [[x y]] (Point. x y)))})

(def custom-transit-json-echo
  (wrap-params identity {:decoders [:transit-json], :transit-json {:handlers readers}}))

(def custom-restful-transit-json-echo
  (wrap-params identity {:transit-json {:handlers readers}}))

(deftest read-custom-transit
  (let [body "[\"^ \", \"~:p\", [\"~#Point\",[1,2]]]"
        parsed-req (custom-transit-json-echo {:content-type "application/transit+json"
                                              :body (stream body)})
        parsed-req2 (custom-restful-transit-json-echo {:content-type "application/transit+json"
                                                       :body (stream body)})]
    (testing "wrap-transit-json-params, transit options"
      (is (= {:p (Point. 1 2)} (:params parsed-req) (:body-params parsed-req))))
    (testing "wrap-restful-params, transit options"
      (is (= {:p (Point. 1 2)} (:params parsed-req2) (:body-params parsed-req2))))))
