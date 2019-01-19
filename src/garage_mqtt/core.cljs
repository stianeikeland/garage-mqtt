(ns garage-mqtt.core
  (:require [garage-mqtt.helpers :refer [log env-or-default]]
            [mqtt]
            [onoff :refer [Gpio]]))

(def mqtt-uri           (env-or-default "MQTT_URI" "mqtt://test.mosquitto.org"))
(def topic-availability (env-or-default "TOPIC_AVAIL" "garage/door/availability"))
(def topic-set          (env-or-default "TOPIC_SET" "garage/door/set"))
(def topic-state        (env-or-default "TOPIC_STATE" "garage/door/state"))

;; GPIO pins for the C.H.I.P:
;; http://www.chip-community.org/index.php/GPIO_Info
;; GPIO pins for RPI:
;; https://www.raspberrypi.org/documentation/usage/gpio/

(def door-sensor-pin    (env-or-default "DOOR_SENSOR_PIN" "408"))
(def garage-toggle-pin  (env-or-default "GARAGE_TOGGLE_PIN" "410"))
(def activity-led-pin)  (env-or-default "ACTIVITY_LED_PIN" "412")
(def invert-door-sensor (cljs.reader/read-string (env-or-default "INVERT_DOOR_SENSOR" "false")))


(def options {:keepalive (* 60 5)
              :will {:topic topic-availability
                     :payload "offline"
                     :qos 0
                     :retain true}})

(def door-sensor (onoff/Gpio. door-sensor-pin "in" "both" (clj->js {:debounceTimeout 50})))
(def garage-toggle (onoff/Gpio. garage-toggle-pin "out"))

(def client (mqtt/connect mqtt-uri (clj->js options)))
(log "Connecting to " mqtt-uri)

(defn toggle-opener! []
  (log "Toggling garage opener..")
  (.write garage-toggle 1 (fn [] ))
  (js/setTimeout #(.write garage-toggle 0 (fn [] )) 100))

(defn read-door-state []
  (let [door-pin (= (.readSync door-sensor) 1)
        door-closed (if invert-door-sensor (not door-pin) door-pin)]
    (if door-closed
      ::closed
      ::open)))

(defn publish-state []
  (let [state (name (read-door-state))
        opts  (clj->js {:retain true})]
    (.publish client topic-state state opts)))

(defn dispatch-command [cmd]
  (case [(read-door-state) cmd]
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
    (log "Got command: " msg-str)
    (when (= topic topic-set)
      (dispatch-command msg-str))))

(.on client "connect" on-connect)
(.on client "message" on-message)

(.watch door-sensor (fn [err val]
                      (log "Door GPIO: " val)
                      (publish-state)))
