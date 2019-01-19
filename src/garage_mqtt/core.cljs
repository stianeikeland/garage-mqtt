(ns garage-mqtt.core
  (:require [mqtt]))

(defn get-env [var default]
  (or (aget js/process.env var)
      default))

(def mqtt-uri           (get-env "MQTT_URI" "mqtt://test.mosquitto.org"))
(def topic-availability (get-env "TOPIC_AVAIL" "garage/door/availability"))
(def topic-set          (get-env "TOPIC_SET" "garage/door/set"))
(def topic-state        (get-env "TOPIC_STATE" "garage/door/state"))

(def options {:keepalive (* 60 5)
              :will {:topic topic-availability
                     :payload "offline"
                     :qos 0
                     :retain true}})

(defn log [what]
  (let [ts (.toISOString (new js/Date (js/Date.now)))]
    (println (str "[" ts "] " what))))

(def client (mqtt/connect mqtt-uri (clj->js options)))
(log (str "Connecting to " mqtt-uri))

(defn toggle-opener! []
  (log "Toggling garage opener.."))

(defn current-state []
  ::closed)

(defn publish-state []
  (let [state (name (current-state))
        opts  (clj->js {:retain true})]
    (.publish client topic-state state opts)))

(defn dispatch-command [cmd]
  (case [(current-state) cmd]
    [::closed "OPEN"]  (toggle-opener!)
    [::open   "CLOSE"] (toggle-opener!)
    :no-match))

(defn on-connect []
  (log "Connected to MQTT!")
  (.subscribe client topic-set)
  (.publish client topic-availability "online")
  (publish-state))

(defn on-message [topic message]
  (let [msg-str (.toString message)]
    (log (str "Got command: " msg-str))
    (when (= topic topic-set)
      (dispatch-command msg-str))))

(.on client "connect" on-connect)
(.on client "message" on-message)
