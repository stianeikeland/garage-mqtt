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

(def door-sensor-pin    (env-or-default "DOOR_SENSOR_PIN" "136")) ;; CSID4
(def invert-door-sensor (read-string (env-or-default "INVERT_DOOR_SENSOR" "false")))

(def garage-toggle-pin-high  (env-or-default "GARAGE_TOGGLE_PIN_HIGH" "139")) ;; CSID7
(def garage-toggle-pin-low  (env-or-default "GARAGE_TOGGLE_PIN_LOW" "137")) ;; CSID5
(def activity-led-pin)  (env-or-default "ACTIVITY_LED_PIN" "134") ;; CSID2

(def options {:keepalive (* 60 5)
              :clientId client-id
              :will {:topic topic-availability
                     :payload "offline"
                     :qos 0
                     :retain true}})

(def door-state (atom ::unknown))

(def door-sensor (onoff/Gpio. door-sensor-pin "in"))
  
(def garage-toggle-high (onoff/Gpio. garage-toggle-pin-high "out"))
(def garage-toggle-low (onoff/Gpio. garage-toggle-pin-low "out"))

(def client (mqtt/connect mqtt-uri (clj->js options)))
(log "Connecting to " (->> mqtt-uri js/URL. .-hostname))

(defn noop [*args])

(defn toggle-opener! []
  (log "Toggling garage opener..")
  (.write garage-toggle-high 1 noop)
  (.write garage-toggle-low 0 noop)
  (js/setTimeout
   #(do
      (.write garage-toggle-high 0 noop)
      (.write garage-toggle-low 1 noop))
   500))

(defn gpio-val->door-state [val]
  (let [door-pin (= val 1)
        door-open (if invert-door-sensor (not door-pin) door-pin)]
    (if door-open
      ::open
      ::closed)))

(defn read-door-state []
  (gpio-val->door-state (.readSync door-sensor)))

(defn publish-state []
  (let [mystate (name @door-state)
        opts    (clj->js {:retain true})]
    (log "Publishing state..")
    (log mystate)
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

(defn poll-door-state-and-notify-if-changed []
  (let [new-state (read-door-state)]
    (when-not (= new-state @door-state)
      (log "Door state changed: " new-state)
      (reset! door-state new-state)
      (publish-state))))

(.on client "connect" on-connect)
(.on client "message" on-message)
(.on client "reconnect" #(log "Reconnect.."))
(.on client "offline" #(log "Offline.."))
(.on client "error" #(log %1))

;; Poll based.. the pin didn't support interrupts :(
(js/setInterval poll-door-state-and-notify-if-changed 1000)

