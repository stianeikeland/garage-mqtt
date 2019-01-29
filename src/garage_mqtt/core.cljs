(ns garage-mqtt.core
  (:require [garage-mqtt.helpers :refer [log env-or-default]]
            [cljs.reader :refer [read-string]]
            [mqtt]
            [onoff :refer [Gpio]]))

(def mqtt-uri           (env-or-default "MQTT_URI" "mqtt://test.mosquitto.org"))
(def topic-availability (env-or-default "TOPIC_AVAIL" "garage/door/availability"))
(def topic-set          (env-or-default "TOPIC_SET" "garage/door/set"))
(def topic-state        (env-or-default "TOPIC_STATE" "garage/door/state"))
(def client-id          (env-or-default "CLIENT_ID" "garage-node"))

;; GPIO pins for the C.H.I.P:
;; http://www.chip-community.org/index.php/GPIO_Info
;; GPIO pins for RPI:
;; https://www.raspberrypi.org/documentation/usage/gpio/

(def door-sensor-pin    (env-or-default "DOOR_SENSOR_PIN" "1016"))
(def garage-toggle-pin  (env-or-default "GARAGE_TOGGLE_PIN" "1017"))
(def activity-led-pin)  (env-or-default "ACTIVITY_LED_PIN" "1018")
(def invert-door-sensor (read-string (env-or-default "INVERT_DOOR_SENSOR" "false")))


(def options {:keepalive (* 60 5)
              :clientId client-id
              :will {:topic topic-availability
                     :payload "offline"
                     :qos 0
                     :retain true}})

(def door-state (atom ::unknown))

(def door-sensor (onoff/Gpio. door-sensor-pin "in" "both" (clj->js {:debounceTimeout 50})))
(def garage-toggle (onoff/Gpio. garage-toggle-pin "out"))

(def client (mqtt/connect mqtt-uri (clj->js options)))
(log "Connecting to " (->> mqtt-uri js/URL. .-hostname))

(defn toggle-opener! []
  (log "Toggling garage opener..")
  (.write garage-toggle 1 (fn [] ))
  (js/setTimeout #(.write garage-toggle 0 (fn [] )) 100))

(defn gpio-val->door-state [val]
  (let [door-pin (= val 1)
        door-closed (if invert-door-sensor (not door-pin) door-pin)]
    (if door-closed
      ::closed
      ::open)))

(defn read-door-state []
  (gpio-val->door-state (.readSync door-sensor)))

(defn publish-state []
  (let [mystate (name @door-state)
        opts    (clj->js {:retain true})]
      (.publish client topic-state mystate opts)))

(defn dispatch-command [cmd]
  (case [@door-state cmd]
    [::closed "OPEN"]  (toggle-opener!)
    [::open   "CLOSE"] (toggle-opener!)
    :no-match))

(defn on-connect []
  (log "Connected to MQTT!")
  (.subscribe client topic-set)
  (.publish client topic-availability "online" (clj->js {:retain true}))
  (swap! door-state read-door-state)
  (publish-state))

(defn on-message [topic message]
  (let [msg-str (.toString message)]
    (log "Got command: " msg-str)
    (when (= topic topic-set)
      (dispatch-command msg-str))))

(defn on-door-pin-change [err val]
  (let [state (gpio-val->door-state val)]
    (log "Door state changed: " state)
    (reset! door-state state)
    (publish-state)))

(.on client "connect" on-connect)
(.on client "message" on-message)
(.watch door-sensor on-door-pin-change)
