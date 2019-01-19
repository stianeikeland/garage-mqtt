(ns garage-mqtt.core
            [onoff :refer [Gpio]]))

(defn get-env [var default]
  (or (aget js/process.env var)
      default))
(def mqtt-uri           (env-or-default "MQTT_URI" "mqtt://test.mosquitto.org"))
(def topic-availability (env-or-default "TOPIC_AVAIL" "garage/door/availability"))
(def topic-set          (env-or-default "TOPIC_SET" "garage/door/set"))
(def topic-state        (env-or-default "TOPIC_STATE" "garage/door/state"))

;; GPIO pins for the C.H.I.P:
;; http://www.chip-community.org/index.php/GPIO_Info
(def door-sensor-pin    (env-or-default "DOOR_SENSOR_PIN" "408"))
(def garage-toggle-pin  (env-or-default "GARAGE_TOGGLE_PIN" "410"))
(def activity-led-pin)  (env-or-default "ACTIVITY_LED_PIN" "412")


(def options {:keepalive (* 60 5)
              :will {:topic topic-availability
                     :payload "offline"
                     :qos 0
                     :retain true}})

(defn log [what]
  (let [ts (.toISOString (new js/Date (js/Date.now)))]
    (println (str "[" ts "] " what))))
(def door-sensor (onoff/Gpio. door-sensor-pin "in" "both" (clj->js {:debounceTimeout 50})))
(def garage-toggle (onoff/Gpio. garage-toggle-pin "out"))

(def client (mqtt/connect mqtt-uri (clj->js options)))
(log (str "Connecting to " mqtt-uri))

(defn toggle-opener! []
  (log "Toggling garage opener..")
  (.write garage-toggle 1 (fn [] ))
  (js/setTimeout #(.write garage-toggle 0 (fn [] )) 100))

(defn current-state []
  (if (= (.readSync door-sensor) 1)
    ::closed
    ::open))

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

(.watch door-sensor (fn [err val]
                      (log "Door GPIO: " val)
                      (publish-state)))
