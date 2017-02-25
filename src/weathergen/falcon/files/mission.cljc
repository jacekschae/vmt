(ns weathergen.falcon.files.mission
  (:require [clojure.string :as str]
            [octet.core :as buf]
            [taoensso.timbre :as log
             :refer-macros (log trace debug info warn error fatal report
                                logf tracef debugf infof warnf errorf fatalf reportf
                                spy get-env log-env)]
            [weathergen.falcon.constants :as c]
            [weathergen.falcon.files :refer [larray fixed-string lstring bitflags
                                             constant read-> spec-if]]
            [weathergen.filesystem :as fs]
            [weathergen.lzss :as lzss]
            [weathergen.util :as util]))

;; There's some weirdness in these next few entries, as it looks like
;; there might be some overlap (or even total replication) with the
;; unit descriptions below. But there's a separation in the C++ code
;; between the inheritance hierarchy and whether the classes let their
;; base classes read from the input stream. So just because one class
;; derives from another doesn't mean that instances of that class read
;; all the base class's data, too.

;; Common structures
(def vu-id (buf/spec :name buf/uint32
                     :creator buf/uint32))

(def campaign-time
  (reify
    octet.spec/ISpecSize
    (size [_]
      (buf/size buf/uint32))

    octet.spec/ISpec
    (read [_ buff pos]
      (let [[size data] (buf/read* buff buf/uint32 {:offset pos})]
        [size
         (let [d (-> data (/ 24 60 60 1000) long)
               h (-> data (mod (* 24 60 60 1000)) (/ 60 60 1000) long)
               m (-> data (mod (* 60 60 1000)) (/ 60 1000) long)
               s (-> data (mod (* 60 1000)) (/ 1000) long)
               ms (mod data 1000)]
           {:day (inc d)
            :hour h
            :minute m
            :second s
            :millisecond ms})]))

    (write [_ buff pos {:keys [day hour minute second millisecond]}]
      (buf/write! buff buf/int32 (-> day
                                     (* 24)
                                     (+ hour)
                                     (* 60)
                                     (+ minute)
                                     (* 60)
                                     (+ second)
                                     (* 1000)
                                     (+ millisecond))))))

;; Ref: f4vu.h
(def vector3
  (buf/spec :x buf/float
            :y buf/float
            :z buf/float))

;; Ref: vuentity.h
(def vu-entity-fields
  [:id                      buf/uint16
   :collision-type          buf/uint16
   :collision-radius        buf/float
   :class-info              (buf/spec :domain buf/ubyte
                                      :class  buf/ubyte
                                      :type   buf/ubyte
                                      :stype  buf/ubyte
                                      :sptype buf/ubyte
                                      :owner  buf/ubyte
                                      :field6 buf/ubyte
                                      :field7 buf/ubyte)
   :update-rate             buf/uint32
   :update-tolerance        buf/uint32
   :fine-update-range       buf/float
   :fine-update-force-range buf/float
   :fine-update-multiplier  buf/float
   :damage-speed            buf/uint32
   :hitpoints               buf/int32
   :major-revision-number   buf/uint16
   :minor-revision-number   buf/uint16
   :create-priority         buf/uint16
   :management-domain       buf/ubyte
   :transferable            buf/ubyte
   :private                 buf/ubyte
   :tangible                buf/ubyte
   :collidable              buf/ubyte
   :global                  buf/ubyte
   :persistent              buf/ubyte
   :padding                 (buf/repeat 3 buf/byte)])

;; Ref: falcent.h
(def falcon4-entity-fields
  [:vu-class-data      (apply buf/spec vu-entity-fields)
   :vis-type           (buf/repeat 7 buf/int16)
   :vehicle-data-index buf/int16
   :data-type          buf/ubyte
   ;; Only a pointer in the sense of indexing into the
   ;; appropriate class table.
   :data-pointer       buf/int32])

;; Ref: campbase.cpp
(def camp-base-fields
  [:id          vu-id
   :entity-type buf/uint16
   :x           buf/int16
   :y           buf/int16
   :z           buf/float
   :spot-time   campaign-time
   :spotted     buf/int16
   :base-flags  buf/int16
   :owner       buf/ubyte
   :camp-id     buf/int16])

(defn read-strings
  "Given a directory with a `name`.idx and `name`.wch files, return a
  function that given an index that will yield the string at that
  index."
  [dir name]
  (let [idx-buf (fs/file-buf (fs/path-combine dir (str name ".idx")))
        wch-buf (fs/file-buf (fs/path-combine dir (str name ".wch")))]
    (binding [octet.buffer/*byte-order* :little-endian]
      (let [n (buf/read idx-buf buf/uint16)
            indices (buf/read idx-buf
                              (buf/repeat n buf/uint16)
                              {:offset 2})
            strings (buf/read wch-buf
                              (fixed-string (nth indices (dec n))))]
        (fn [n]
          (subs strings (nth indices n) (nth indices (inc n))))))))

(def unit-class-data
  ;; Source: entity.cpp::LoadUnitData and UcdFile.cs
  (buf/spec :index buf/int32
            :num-elements (buf/repeat c/VEHICLE_GROUPS_PER_UNIT
                                      buf/int32)
            :vehicle-type  (buf/repeat c/VEHICLE_GROUPS_PER_UNIT
                                       buf/int16)
            :vehicle-class (buf/repeat c/VEHICLE_GROUPS_PER_UNIT
                                       (buf/repeat 8 buf/ubyte))
            :flags buf/uint16
            :name (fixed-string 20)
            :padding buf/int16
            :movement-type buf/int32
            :movement-speed buf/int16
            :max-range buf/int16
            :fuel buf/int32
            :rate buf/int16
            :pt-data-index buf/int16
            :scores (buf/repeat c/MAXIMUM_ROLES buf/ubyte)
            :role buf/ubyte
            :hit-chance (buf/repeat c/MOVEMENT_TYPES buf/ubyte)
            :strength (buf/repeat c/MOVEMENT_TYPES buf/ubyte)
            :range (buf/repeat c/MOVEMENT_TYPES buf/ubyte)
            :detection (buf/repeat c/MOVEMENT_TYPES buf/ubyte)
            :damage-mod (buf/repeat (inc c/OtherDam) buf/ubyte)
            :radar-vehicle buf/ubyte
            :padding2 buf/byte
            :special-index buf/int16
            :icon-index buf/uint16
            :padding3 (buf/repeat 2 buf/byte)))

;; Ref: entity.h
(def objective-class-data
  (buf/spec :index buf/int16 ; Matches the index in the class table
            :name (fixed-string 20)
            :data-rate buf/int16 ; Sorte Rate and other cool data
            :deag-distance buf/int16 ; Distance to deaggregate at.
            :pt-data-index buf/int16 ; Index into pt header data table
            :detection (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; Detection ranges
            :damage-mod (buf/repeat (inc c/OtherDam) buf/ubyte) ; How much each type will hurt me (% of strength applied)
            :icon-index buf/int16 ; Index to this objective's icon type
            :mystery buf/ubyte    ; Not sure what this is
            :features buf/ubyte ; Number of features in this objective
            :radar-feature buf/ubyte ; ID of the radar feature for this objective
            :first-feature buf/int16 ; Index of first feature entry
            ))

;; Ref: entity.h
(def vehicle-class-data
  (buf/spec     :index buf/int16      ; descriptionIndex pointing here
                :hit-points buf/int16 ; Damage this thing can take
                :flags buf/uint32     ; see VEH_ flags in vehicle.h
                :name (fixed-string 15)
                :nctr (fixed-string 5)
                :rcs-factor buf/float ; log2( 1 + RCS relative to an F16 )
                :max-wt buf/int32     ; Max loaded weight in lbs.
                :empty-wt buf/int32   ; Empty weight in lbs.
                :fuel-wt buf/int32    ; Weight of max fuel in lbs.
                :fuel-econ buf/int16  ; Fuel usage in lbs./min.
                :engine-sound buf/int16 ; SoundFX sample index of corresponding engine sound
                :high-alt buf/int16     ; in hundreds of feet
                :low-alt buf/int16      ; in hundreds of feet
                :cruise-alt buf/int16   ; in hundreds of feet
                :max-speed buf/int16   ; Maximum vehicle speed, in kph
                :radar-type buf/int16 ; Index into RadarDataTable
                :number-of-pilots buf/int16 ; # of pilots (for eject)
                :rack-flags buf/uint16 ; 0x01 means hardpoint 0 needs a rack, 0x02 -> hdpt 1, etc
                :visible-flags buf/uint16 ; 0x01 means hardpoint 0 is visible, 0x02 -> hdpt 1, etc
                :callsign-index buf/ubyte
                :callsign-slots buf/ubyte
                :hit-chance (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; // Vehicle hit chances (best hitchance bitand bonus)
                :strength (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; // Combat strengths (full strength only) (calculated)
                :range (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; // Firing ranges (full strength only) (calculated)
                :detection (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; // Electronic detection ranges
                :weapon (buf/repeat c/HARDPOINT_MAX buf/int16); // Weapon id of weapons (or weapon list)
                :weapons (buf/repeat c/HARDPOINT_MAX buf/ubyte); // Number of shots each (fully supplied)
                :damage-mod (buf/repeat (inc c/OtherDam) buf/ubyte) ; // How much each type will hurt me (% of strength applied
                :mystery (buf/repeat 3 buf/ubyte)
                ))

;; Ref: entity.h
(def feature-class-data
  (buf/spec :index buf/int16 ; descriptionIndex pointing here
            :repair-time buf/int16 ; How long it takes to repair
            :priority buf/byte    ; Display priority
            :padding1 buf/byte
            :flags buf/uint16     ; See FEAT_ flags in feature.h
            :name (fixed-string 20)    ; 'Control Tower'
            :hit-points buf/int16      ; Damage this thing can take
            :height buf/int16       ; Height of vehicle ramp, if any
            :angle buf/float            ; Angle of vehicle ramp, if any
            :radar-type buf/int16   ; Index into RadarDataTable
            :detection (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; Electronic detection ranges
            :damage-mod (buf/repeat (inc c/OtherDam) buf/ubyte) ; How much each type will hurt me (% of strength appl
            :padding2 (buf/repeat 3 buf/ubyte)))

;; Ref: entity.h
(def feature-entry-data
  (buf/spec :index buf/int16        ; Entity class index of feature
            :flags buf/uint16
            :entity-class (buf/repeat 8 buf/ubyte) ; Entity class array of feature
            :value buf/ubyte ; % loss in operational status for destruction
            :padding1 (buf/repeat 3 buf/byte)
            :offset vector3
            :facing buf/int16
            :padding2 (buf/repeat 2 buf/byte)))

(def point-header-data
  (buf/spec :obj-id buf/int16 ; ID of the objective this belongs to
            :type buf/ubyte    ; The type of pt data this contains
            :count buf/ubyte ; Number of points
            :features (buf/repeat c/MAX_FEAT_DEPEND buf/ubyte) ; Features this list
                                                               ; depends on (# in
                                                               ; objective's feature
                                        ; list)
            :padding buf/byte
            :data buf/int16             ; Other data (runway heading, for example)
            :sin-heading buf/float
            :cos-heading buf/float
            :first buf/int16            ; Index of first point
            :tex-idx buf/int16          ; texture to apply to this runway
            :runway-num buf/byte ; -1 if not a runway, indicates which runway this list
                                 ; applies to
            :ltrt buf/byte       ; put base pt to rt or left
            :next-header buf/int16 ; Index of next header, if any
            ))

(defn extension
  [file-name]
  (let [i (str/last-index-of file-name ".")]
    (subs file-name i)))

(defn file-type
  [file-name]
  (get {".CMP" :campaign-info
        ".OBJ" :objectives
        ".OBD" :objective-deltas
        ".UNI" :units
        ".TEA" :teams
        ".EVT" :events
        ".POL" :primary-objectives
        ".PLT" :pilots
        ".PST" :persistent-objects
        ".WTH" :weather
        ".VER" :version
        ".TE"  :victory-conditions}
       (str/upper-case (extension file-name))
       :unknown))

(defmulti read-embedded-file*
  (fn [type entry buf database]
    type))

(defn read-embedded-file
  [type entry buf database]
  (log/debug "read-embedded-file"
             :type type
             :file-name (:file-name entry))
  (read-embedded-file* type entry buf database))

(def directory-entry
  (buf/spec :file-name (lstring buf/ubyte)
            :offset buf/uint32
            :length buf/uint32))

(defn find-install-dir
  "Given a path somewhere in the filesystem, figure out which theater
  it's in and return a map describing it."
  [path]
  (loop [dir (fs/parent path)]
    (when dir
      (if (every? #(fs/exists? (fs/path-combine dir %))
                  ["Bin" "Data" "Tools" "User"])
        dir
        (recur (fs/parent dir))))))

(defn campaign-dir
  "Return the path to the campaign directory."
  [installation theater]
  (fs/path-combine (:data-dir installation)
                   (:campaigndir theater)))

(defn object-dir
  "Return the path to the objects directory."
  [installation theater]
  #_(log/debug "object-dir"
             :installation installation
             :theater theater)
  (fs/path-combine (:data-dir installation)
                   (:objectdir theater)))

(defn parse-theater-def-line
  "Parse a line from the theater.tdf file."
  [line]
  (if-let [idx (str/index-of line " ")]
    [(-> line (subs 0 idx) keyword)
     (-> line (subs (inc idx)))]))

(defn read-theater-def
  "Reads the theater TDF from the given path, relative to `data-dir`."
  [data-dir path]
  (->> path
       (fs/path-combine data-dir)
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       (remove #(.startsWith % "#"))
       (map parse-theater-def-line)
       (into {})))

(defn read-theater-defs
  "Read, parse, and return information about the installled theaters
  from the theater list."
  [data-dir]
  (->> "Terrdata/theaterdefinition/theater.lst"
       (fs/path-combine data-dir)
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove #(.startsWith % "#"))
       (remove str/blank?)
       (map #(read-theater-def data-dir %))))

(defn read-id-list-file
  "Reads an id list file consisting of pairs of names and ids. Returns
  a seq of those pairs."
  [installation path]
  (->> path
       (fs/path-combine (:data-dir installation))
       fs/file-text
       str/split-lines
       (map str/trim)
       (remove str/blank?)
       ;; The equals is because one of the files uses it, I think
       ;; mistakenly
       (map #(str/split % #"[ \t=]+"))
       (map (fn [[name id]]
              [name (or (util/str->long id)
                        (do (log/error "Error parsing image id"
                                       :name name
                                       :id id
                                       :path path)
                            nil))]))))

(defn read-id-list
  "Read in the id list file with `id`"
  [installation id]
  (let [pairs (->> (-> installation
                       :art-dir
                       (fs/path-combine (str id ".LST")))
                   fs/file-text
                   str/split-lines
                   (map str/trim)
                   (remove str/blank?)
                   (mapcat #(read-id-list-file installation %)))]
    {:name->id (zipmap (map first pairs) (map second pairs))
     :id->name (zipmap (map second pairs) (map first pairs))}))

(defn read-image-ids
  "Read in the image IDs in this installation. Returns a map with
  keys :id->name and :name->id mapping in each direction."
  [installation]
  (read-id-list installation "IMAGEIDS"))

(defn read-user-ids
  "Read in the user IDs in this installation. Returns a map with
  keys :id->name and :name->id mapping in each direction."
  [installation]
  (read-id-list installation "USERIDS"))

(defn load-installation
  "Return information about the installed theaters."
  [install-dir]
  (let [data-dir (fs/path-combine install-dir "Data")
        art-dir (fs/path-combine data-dir "Art")]
    {:install-dir install-dir
     :data-dir data-dir
     :art-dir art-dir
     :theaters (read-theater-defs data-dir)}))

(defn find-theater
  "Given the path to a mission file, return the theater it's in."
  [installation path]
  (->> installation
       :theaters
       (filter #(fs/ancestor? (campaign-dir installation %)
                              path))
       first))

(defn load-initial-database
  "Load the files in known locations needed to process a mission in a
  given theater."
  [installation theater]
  (->> (for [[file k spec] [["FALCON4.ct" :class-table (apply buf/spec falcon4-entity-fields)]
                            ["FALCON4.UCD" :unit-class-data unit-class-data]
                            ["FALCON4.OCD" :objective-class-data objective-class-data]
                            ["FALCON4.VCD" :vehicle-class-data vehicle-class-data]
                            ["FALCON4.FCD" :feature-class-data feature-class-data]
                            ["falcon4.fed" :feature-entry-data feature-entry-data]
                            ["FALCON4.PHD" :point-header-data point-header-data]]]
         (let [path (fs/path-combine
                     (object-dir installation theater)
                     file)
               buf  (fs/file-buf path)]
           [k
            (binding [octet.buffer/*byte-order* :little-endian]
              (map-indexed (fn [i v]
                             (assoc v ::index i))
                           (buf/read (->> file
                                          (fs/path-combine (object-dir installation theater))
                                          fs/file-buf)
                                     (larray buf/uint16 spec))))]))
       (into {})
       (merge { ;; These next couple might need to be generalized, like to a map
               ;; from the type to the ids in it.
               :image-ids            (read-image-ids installation)
               :user-ids             (read-user-ids installation)
               :strings              (read-strings (campaign-dir installation theater)
                                                   "Strings")})))

(defn read-embedded-files
  "Reads and parses a .tac/.cmp file, returning a map from the
  embedded file type to its contents."
  [path database]
  (let [buf (fs/file-buf path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      ;; TODO: Make this whole thing into a spec
      (let [dir-offset (buf/read buf buf/uint32)
            dir-file-count (buf/read buf buf/uint32 {:offset dir-offset})
            directory (buf/read buf (buf/repeat dir-file-count directory-entry)
                                {:offset (+ dir-offset 4)})
            files (for [entry directory
                        :let [type (-> entry :file-name file-type)]]
                    (assoc entry
                           :type type
                           :data (read-embedded-file type entry buf database)))]
        ;; Ensure that there's exactly one file per type
        (assert (->> files
                     (map :type)
                     distinct
                     count
                     (= (count files))))
        (zipmap (map :type files) (map :data files))))))

(defn merge-objective-deltas
  "Given objectives and objective deltas, merge the deltas and return
  an updated objectives list."
  [objectives deltas]
  (let [objectives-map (zipmap (map :id objectives) objectives)]
    (->> deltas
         (reduce (fn [objectives-map delta]
                    (update objectives-map (:id delta) merge delta))
                 objectives-map)
         vals
         vec)))

;; TODO: Consider renaming this read-database, and referring to the resulting object
;; as the database.
(defn read-mission
  "Given a path to a mission (.cam/.tac/.trn) file, read,
  parse, and return it."
  [path]
  (let [install-dir   (find-install-dir path)
        installation  (load-installation install-dir)
        theater       (find-theater installation path)
        database      (load-initial-database installation theater)
        mission-files (read-embedded-files path database)
        {:keys [theater-name scenario]} (->> mission-files :campaign-info)
        names         (read-strings (campaign-dir installation theater)
                                    theater-name)
        scenario-path (fs/path-combine (fs/parent path)
                                       (str scenario (extension path)))
        scenario-files (read-embedded-files scenario-path database)]
    (merge database
           mission-files
           ;; TODO: Figure out if we need to merge persistent objects
           {:objectives     (merge-objective-deltas
                             (:objectives scenario-files)
                             (:objective-deltas mission-files))
            :scenario-files scenario-files
            :path           path
            :names          names
            :installation   installation
            :theater        theater})))


;; Campaign details file
(def team-basic-info
  (buf/spec :flag buf/ubyte
            :color buf/ubyte
            :name (fixed-string 20)
            :motto (fixed-string 200)))

(def squadron-info
  (buf/spec :x                 buf/float
            :y                 buf/float
            :id                vu-id
            :description-index buf/int16
            :name-id           buf/int16 ; The UI's id into name and patch data
            :airbase-icon      buf/int16
            :squadron-patch    buf/int16
            :specialty         buf/ubyte
            :current-strength  buf/ubyte ; # of current active aircraft
            :country           buf/ubyte
            :airbase-name      (fixed-string 40)
            :padding           buf/byte))

(def event-node
  (buf/spec :x buf/int16
            :y buf/int16
            :time campaign-time
            :flags buf/ubyte
            :team buf/ubyte
            :padding (buf/repeat 2 buf/byte)
            :event-text buf/int32 ; Pointer - no meaning in file
            :ui-event-node buf/int32 ; Pointer - no meaning in file
            :event-text (lstring buf/uint16)))

(def cmp-spec
  (buf/spec :current-time       campaign-time
            :te-start           campaign-time
            :te-time-limit      campaign-time
            :te-victory-points  buf/int32
            :te-type            buf/int32
            :te-num-teams       buf/int32
            :te-num-aircraft    (buf/repeat 8 buf/int32)
            :te-num-f16s        (buf/repeat 8 buf/int32)
            :te-team            buf/int32
            :te-team-points     (buf/repeat 8 buf/int32)
            :te-flags           buf/int32
            :team-info          (buf/repeat 8 team-basic-info)
            :last-major-event   buf/uint32
            :last-resupply      buf/uint32
            :last-repair        buf/uint32
            :last-reinforcement buf/uint32
            :time-stamp         buf/int16
            :group              buf/int16
            :ground-ratio       buf/int16
            :air-ratio          buf/int16
            :air-defense-ratio  buf/int16
            :naval-ratio        buf/int16
            :brief              buf/int16
            :theater-size-x     buf/int16
            :theater-size-y     buf/int16
            :current-day        buf/ubyte
            :active-team        buf/ubyte
            :day-zero           buf/ubyte
            :endgame-result     buf/ubyte
            :situation          buf/ubyte
            :enemy-air-exp      buf/ubyte
            :enemy-ad-exp       buf/ubyte
            :bullseye-name      buf/ubyte
            :bullseye-x         buf/int16
            :bullseye-y         buf/int16
            :theater-name       (fixed-string 40)
            :scenario           (fixed-string 40)
            :save-file          (fixed-string 40)
            :ui-name            (fixed-string 40)
            :player-squadron-id vu-id
            :recent-event-entries (larray buf/int16 event-node)
            :priority-event-entries (larray buf/int16 event-node)
            :map                (larray buf/int16 buf/ubyte)
            :last-index-num     buf/int16
            :squadron-info      (larray buf/int16 squadron-info)
            :tempo              buf/ubyte
            :creator-ip         buf/int32
            :creation-time      buf/int32
            :creation-rand      buf/int32))

(defmethod read-embedded-file* :campaign-info
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :uncompressed-size buf/int32)
          {:keys [compressed-size
                  uncompressed-size]} (buf/read buf header-spec {:offset offset})
          ;; For some weird reason, the compressed size includes the field
          ;; for the uncompressed size
          adjusted-compressed-size (- compressed-size (buf/size buf/int32))
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            adjusted-compressed-size
                            uncompressed-size)]
      (into (sorted-map) (buf/read data cmp-spec)))))

;; Objectives file

;; Ref: objectiv.h
(def objective-link
  (buf/spec :costs (buf/repeat c/MOVEMENT_TYPES buf/ubyte) ; Cost to go here depending
                                                           ; on movement type
            :id    vu-id))

;; Ref: objectiv.cpp
;; TODO: Combine this with the objective delta stuff
(def objective-fields
  (util/concatv [:objective-type buf/uint16]
                camp-base-fields
                [:last-repair  campaign-time
                 :obj-flags    buf/uint32
                 :supply       buf/ubyte
                 :fuel         buf/ubyte
                 :losses       buf/ubyte
                 ;; This gets weird - see objectiv.cpp:251
                 :f-status      (larray buf/ubyte buf/ubyte)
                 :priority     buf/ubyte
                 :name-id      buf/int16
                 :parent       vu-id
                 :first-owner  buf/ubyte
                 :links        (larray buf/ubyte objective-link)
                 :radar-data   (spec-if :has-radar-data buf/byte pos?
                                        :detect-ratio
                                        (buf/repeat c/NUM_RADAR_ARCS buf/float)
                                        (constant nil))]))

(defmethod read-embedded-file* :objectives
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :num-objectives buf/int16
                                :uncompressed-size buf/int32
                                :compressed-size buf/int32)
          {:keys [num-objectives
                  compressed-size
                  uncompressed-size]} (buf/read buf
                                                header-spec
                                                {:offset offset})
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            compressed-size
                            uncompressed-size)]
      #_(log/debug "read objectives"
                 :num-objectives num-objectives
                 :compressed-size compressed-size
                 :uncompressed-size uncompressed-size)
      (buf/read data
                (buf/repeat num-objectives
                            (apply buf/spec objective-fields))))))

;; Objectives delta file
(def objective-delta
  (buf/spec :id vu-id
            :last-repair campaign-time
            :owner buf/ubyte
            :supply buf/ubyte
            :fuel buf/ubyte
            :losses buf/ubyte
            :f-status (larray buf/ubyte buf/ubyte)))

(defmethod read-embedded-file* :objective-deltas
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :num-deltas buf/int16
                                :uncompressed-size buf/int32)
          {:keys [num-deltas
                  compressed-size
                  uncompressed-size]} (buf/read buf
                                                header-spec
                                                {:offset offset})
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            compressed-size
                            uncompressed-size)]
      (buf/read data
                (buf/repeat num-deltas objective-delta)))))

;; Victory conditions
(defmethod read-embedded-file* :victory-conditions
  [_ {:keys [offset length] :as entry} buf _]
  (buf/read buf (buf/string length) {:offset offset}))

;; Team definition file

(def team-air-action
  (buf/spec :start campaign-time
            :stop campaign-time
            :objective vu-id
            :last-objective vu-id
            :type buf/ubyte
            :padding (buf/repeat 3 buf/byte)))

(def team-ground-action
  (buf/spec :time campaign-time
            :timeout campaign-time
            :objective vu-id
            :type buf/ubyte
            :tempo buf/ubyte
            :points buf/ubyte))

(def team-status
  (buf/spec :air-defence-vehicles buf/uint16
            :aircraft buf/uint16
            :ground-vehicles buf/uint16
            :ships buf/uint16
            :supply buf/uint16
            :fuel buf/uint16
            :airbases buf/uint16
            :supply-level buf/ubyte
            :fuel-level buf/ubyte))

(def atm-airbase
  (buf/spec :id vu-id
            :schedule (buf/repeat c/ATM_MAX_CYCLES buf/ubyte)))

(def tasking-manager-fields
  [:id vu-id
   :entity-type buf/uint16
   :manager-flags buf/int16
   :owner buf/ubyte])

(def mission-request
  ;; Ref AirTaskingManager.cs
  (buf/spec
   :requester      vu-id
   :target         vu-id
   :secondary      vu-id
   :pak            vu-id
   :who            buf/ubyte
   :vs             buf/ubyte
   :padding        (buf/repeat 2 buf/byte)
   :tot            buf/uint32
   :tx             buf/int16
   :ty             buf/int16
   :flags          buf/uint32
   :caps           buf/int16
   :target-num     buf/int16
   :speed          buf/int16
   :match-strength buf/int16
   :priority       buf/int16
   :tot-type       buf/ubyte
   :action-type    buf/ubyte
   :mission        buf/ubyte
   :aircraft       buf/ubyte
   :context        buf/ubyte
   :roe-check      buf/ubyte
   :delayed        buf/ubyte
   :start-block    buf/ubyte
   :final-block    buf/ubyte
   :slots          (buf/repeat 4 buf/ubyte)
   :min-to         buf/byte ; Yes, signed
   :max-to         buf/byte ; Yes, signed
   :padding        (buf/repeat 3 buf/byte)))

(def air-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16
                :average-ca-strength buf/int16
                :average-ca-missions buf/int16
                :sample-cycles buf/ubyte
                :airbases (larray buf/ubyte atm-airbase)
                :cycle buf/ubyte
                ;; This differs between the C# and the CPP...
                :mission-requests (larray buf/int16 mission-request)])))

(def ground-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16])))

(def naval-tasking-manager
  (apply buf/spec
         (into tasking-manager-fields
               [:flags buf/int16])))

(def team
  (buf/spec :id vu-id
            :entity-type buf/uint16
            :who buf/ubyte
            :c-team buf/ubyte
            :flags buf/uint16
            :members (buf/repeat 8 buf/ubyte)
            :stance (buf/repeat 8 buf/int16)
            :first-colonel buf/int16
            :first-commander buf/int16
            :first-wingman buf/int16
            :last-wingman buf/int16
            :air-experience buf/ubyte
            :air-defense-experience buf/ubyte
            :ground-experience buf/ubyte
            :naval-experience buf/ubyte
            :initiatve buf/int16
            :supply-available buf/uint16
            :fuel-available buf/uint16
            :replacements-available buf/uint16
            :player-rating buf/float
            :last-player-mission campaign-time
            :current-stats team-status
            :start-stats team-status
            :reinforcement buf/int16
            :bonus-objs (buf/repeat 20 vu-id)
            :bonus-time (buf/repeat 20 buf/uint32)
            ;; TODO: These next few should read into something with
            ;; names rather than an array
            :obj-type-priority (buf/repeat 36 buf/ubyte)
            :unit-type-priority (buf/repeat 20 buf/ubyte)
            :mission-priority (buf/repeat 41 buf/ubyte)
            :max-vehicle (buf/repeat 4 buf/ubyte)
            :team-flag buf/ubyte
            :team-color buf/ubyte
            :equipment buf/ubyte
            :name (fixed-string 20)
            :motto (fixed-string 200)
            :ground-action team-ground-action
            :defensive-air-action team-air-action
            :offensive-air-action team-air-action))

(def team-record
  (buf/spec :team team
            :air-tasking-manager air-tasking-manager
            :ground-tasking-manager ground-tasking-manager
            :naval-tasking-manager naval-tasking-manager))

(defmethod read-embedded-file* :teams
  [_ {:keys [offset length] :as entry} buf _]
  (binding [octet.buffer/*byte-order* :little-endian]
    (buf/read buf
              (larray buf/int16 team-record)
              {:offset offset})))

;; Version file
(defmethod read-embedded-file* :version
  [_ {:keys [offset length] :as entry} buf _]
  (let [version-string (buf/read buf (buf/string length) {:offset offset})]
    {:version #?(:clj (Long. version-string)
                 :cljs (-> version-string js/Number. .valueOf long))}))

;; Units file
(def waypoint
  (reify
    octet.spec/ISpec
    (read [_ buf pos]
      (read-> buf
              pos
              (constantly (buf/spec
                           :haves        (bitflags buf/ubyte
                                                   {:deptime 0x01
                                                    :target  0x02})
                           :grid-x       buf/int16
                           :grid-y       buf/int16
                           :grid-z       buf/int16
                           :arrive       campaign-time
                           :action       buf/ubyte
                           :route-action buf/ubyte
                           :formation    buf/ubyte
                           :flags        buf/uint32))
              (fn [{:keys [haves]}]
                (if (haves :target)
                  (buf/spec :target-id vu-id
                            :target-building buf/ubyte)
                  (constant {:target-id {:name    0
                                         :creator 0}
                             :target-building 255})))
              (fn [{:keys [haves arrive]}]
                (if (haves :deptime)
                  (buf/spec :depart campaign-time)
                  (constant {:depart arrive})))))

    ;; TODO: Implement write
    ))

(def unit-fields
  (into camp-base-fields
        [:last-check    campaign-time
         :roster        buf/int32
         :unit-flags    (bitflags buf/int32 {:dead        0x1
                                             :b3          0x2
                                             :assigned    0x04
                                             :ordered     0x08
                                             :no-pllaning 0x10
                                             :parent      0x20
                                             :engaged     0x40
                                             :b1          0x80
                                             :scripted    0x100
                                             :commando    0x200
                                             :moving      0x400
                                             :refused     0x800
                                             :has-ecm     0x1000
                                             :cargo       0x2000
                                             :combat      0x4000
                                             :broken      0x8000
                                             :losses      0x10000
                                             :inactive    0x20000
                                             :fragmented  0x40000
                                             ;; Ground unit specific
                                             :targeted    0x100000
                                             :retreating  0x200000
                                             :detached    0x400000
                                             :supported   0x800000
                                             :temp-dest   0x1000000
                                             ;; Air unit specific
                                             :final       0x100000
                                             :has-pilots  0x200000
                                             :diverted    0x400000
                                             :fired       0x800000
                                             :locked      0x1000000
                                             :ia-kill     0x2000000
                                             :no-abort    0x4000000})
         :destination-x buf/int16
         :destination-y buf/int16
         :target-id     vu-id
         :cargo-id      vu-id
         :moved         buf/ubyte
         :losses        buf/ubyte
         :tactic        buf/ubyte
         :current-wp    buf/uint16
         :name-id       buf/int16
         :reinforcement buf/int16
         :waypoints     (larray buf/uint16 waypoint)]))

;; Air units

(def pilot
  (buf/spec :id               buf/int16
            :skill-and-rating buf/ubyte
            :status           buf/ubyte
            :aa-kills         buf/ubyte
            :ag-kills         buf/ubyte
            :as-kills         buf/ubyte
            :an-kills         buf/ubyte
            :missions-flown   buf/int16))

(def loadout
  (buf/spec :id    (buf/repeat 16 buf/uint16) ; Widened from byte in version 73
            :count (buf/repeat 16 buf/ubyte)))

;; Ref: flight.cpp::FlightClass ctor
(def flight
  (apply buf/spec
         (into unit-fields
               [:type              (constant :flight)
                :pos-z             buf/float
                :fuel-burnt        buf/int32
                :last-move         campaign-time
                :last-combat       campaign-time
                :time-on-target    campaign-time
                :mission-over-time campaign-time
                :mission-target    buf/int16
                :loadouts          (larray buf/ubyte loadout)
                :mission           buf/ubyte
                :old-mission       buf/ubyte
                :last-direction    buf/ubyte
                :priority          buf/ubyte
                :mission-id        buf/ubyte
                :eval-flags        buf/ubyte ; Only shows up in lightning's tools - not on the PMC wiki. It's also in the freefalcon source.
                :mission-context   buf/ubyte
                :package           vu-id
                :squadron          vu-id
                :requester         vu-id
                :slots             (buf/repeat 4 buf/ubyte)
                :pilots            (buf/repeat 4 buf/ubyte)
                :plane-stats       (buf/repeat 4 buf/ubyte)
                :player-slots      (buf/repeat 4 buf/ubyte)
                :last-player-slot  buf/ubyte
                :callsign-id       buf/ubyte
                :callsign-num      buf/ubyte
                :refuel-quantity   buf/uint32 ; >= 72
                ])))

(def squadron
  (apply buf/spec
         (into unit-fields
               [:type           (constant :squadron)
                :fuel           buf/int32
                :specialty      buf/ubyte
                :stores         (buf/repeat 600 buf/ubyte)
                :pilots         (buf/repeat 48 pilot)
                :schedule       (buf/repeat 16 buf/int32)
                :airbase-id     vu-id
                :hot-spot       vu-id
                :rating         (buf/repeat 16 buf/ubyte)
                :aa-kills       buf/int16
                :ag-kills       buf/int16
                :as-kills       buf/int16
                :an-kills       buf/int16
                :missions-flown buf/int16
                :mission-score  buf/int16
                :total-losses   buf/ubyte
                :pilot-losses   buf/ubyte
                :squadron-patch buf/ubyte])))

(def package
  (let [package-common (apply buf/spec
                              (into unit-fields
                                    [:type              (constant :package)
                                     :elements          (larray buf/ubyte vu-id)
                                     :interceptor       vu-id
                                     :awacs             vu-id
                                     :jstar             vu-id
                                     :ecm               vu-id
                                     :tanker            vu-id
                                     :wait-cycles       buf/ubyte]))]
    (reify
      octet.spec/ISpec
      (read [_ buf pos]
        (read-> buf
                pos
                (constantly  package-common)
                (fn [{:keys [unit-flags wait-cycles] :as val}]
                  (if (and (zero? wait-cycles)
                           (unit-flags :final))
                    (buf/spec
                     :requests buf/int16
                     :responses buf/int16
                     :mission-request (buf/spec
                                       :mission buf/int16
                                       :context buf/int16
                                       :requester vu-id
                                       :target vu-id
                                       :tot buf/uint32
                                       :action-type buf/ubyte
                                       :priority buf/int16
                                       :package-flags (constant 0)))
                    (buf/spec
                     :flights buf/ubyte
                     :wait-for buf/int16
                     :iax buf/int16
                     :iay buf/int16
                     :eax buf/int16
                     :eay buf/int16
                     :bpx buf/int16
                     :bpy buf/int16
                     :tpx buf/int16
                     :tpy buf/int16
                     :takeoff campaign-time
                     :tp-time campaign-time
                     :package-flags buf/uint32
                     :caps buf/int16
                     :requests buf/int16
                     :responses buf/int16
                     :ingress-waypoints (larray buf/ubyte waypoint)
                     :egress-waypoints (larray buf/ubyte waypoint)
                     :mission-request mission-request))))))))

;; Land units

(def ground-unit-fields
  (into unit-fields
        [:orders   buf/ubyte
         :division buf/int16
         :aobj     vu-id]))

(def brigade
  (apply buf/spec
         (into ground-unit-fields
               [:type     (constant :brigade)
                :elements (larray buf/ubyte vu-id)])))

(def battalion
  (apply buf/spec
         (into ground-unit-fields
               [:type (constant :battalion)
                :last-move campaign-time
                :last-combat campaign-time
                :parent-id vu-id
                :last-obj vu-id
                :supply buf/ubyte
                :fatigue buf/ubyte
                :morale buf/ubyte
                :heading buf/ubyte
                :final-heading buf/ubyte
                :position buf/ubyte])))

;; Sea units

(def task-force
  (apply buf/spec
         (into unit-fields
               [:type (constant :task-force)
                :orders buf/ubyte
                :supply buf/ubyte])))

;; unit-record

(defn class-info
  "Retrieves the unit class info (the most important elements of which
  are things like domain and type) for a given unit type."
  [{:keys [class-table] :as database} type-id]
  (let [class-entry (nth class-table
                         (- type-id c/VU_LAST_ENTITY_TYPE))]
    (-> class-entry
        :vu-class-data
        :class-info)))

(defn unit-record
  "Returns an octet spec for unit records against the given
  class table."
  [database]
  (reify
    octet.spec/ISpec
    (read [_ buf pos]
      (let [type-id (buf/read buf buf/int16 {:offset pos})]
        #_(log/debug "unit-record reading"
                   :pos pos
                   :unit-type unit-type)
        (if (zero? type-id)
          (do
            #_(log/debug "unit-record: found zero unit-type entry..")
            [2 nil])
          (let [{:keys [domain type]} (class-info
                                       database
                                       type-id)
                ;; _ (log/debug "unit-record decoding"
                ;;              :pos pos
                ;;              :domain domain
                ;;              :type type)
                spc (condp = [domain type]
                      [c/DOMAIN_AIR  c/TYPE_FLIGHT] flight
                      [c/DOMAIN_AIR  c/TYPE_PACKAGE] package
                      [c/DOMAIN_AIR  c/TYPE_SQUADRON] squadron
                      [c/DOMAIN_LAND c/TYPE_BATTALION] battalion
                      [c/DOMAIN_LAND c/TYPE_BRIGADE] brigade
                      [c/DOMAIN_SEA  c/TYPE_TASKFORCE] task-force)
                [datasize data] (try
                                  (buf/read* buf
                                             spc
                                             {:offset (+ pos 2)})
                                  (catch #?(:clj Throwable
                                            :cljs :default)
                                      x
                                      (log/error x
                                                 "unit-record read"
                                                 :domain domain
                                                 :type type
                                                 :pos pos)
                                      (throw x)))]
            #_(log/debug "unit-record decoded"
                         :data (keys data)
                         :datasize datasize)
            [(+ datasize 2) (assoc data :type-id type-id)]))))

    ;; TODO : implement write
    ))

(defn ordinal-suffix
  "Returns the appropriate ordinal suffix for a given number. I.e. \"st\" for 1, to give \"1st\"."
  [n {:keys [strings] :as database}]
  (cond
    (and (= 1 (mod n 10))
         (not= n 11))
    (strings 15)

    (and (= 2 (mod n 10))
         (not= n 12))
    (strings 16)

    (and (= 3 (mod n 10))
         (not= n 13))
    (strings 17)

    :else
    (strings 18)))

(defn partial=
  "Returns true if the coll1 and coll2 have corresponding elements
  equal, ignoring any excess."
  [coll1 coll2]
  (every? identity (map = coll1 coll2)))

(defn get-size-name
  "Returns the size portion of a unit name"
  [unit database]
  (let [{:keys [type-id]} unit
        {:keys [type domain]} (class-info database type-id)
        {:keys [strings]} database]
    #_(log/debug "get-size-name" :domain domain :type type)
    (strings
     (condp partial= [domain type]
       [c/DOMAIN_AIR  c/TYPE_SQUADRON]   610
       [c/DOMAIN_AIR  c/TYPE_FLIGHT]     611
       [c/DOMAIN_AIR  c/TYPE_PACKAGE]    612
       [c/DOMAIN_LAND c/TYPE_BRIGADE]   614
       [c/DOMAIN_LAND c/TYPE_BATTALION] 615
       [c/DOMAIN_SEA]               616
       617))))

(defn data-table
  "Return the appropriate data table."
  [database data-type]
  (let [k (condp = data-type
            c/DTYPE_UNIT :unit-class-data
            nil)]
    (if k
      (get database k)
      ;; This is a TODO
      (vec (repeat 10000 {})))))

(defn class-data
  "Return the class data appropriate to the type."
  [database type-id]
  (let [{:keys [class-table]} database
        {:keys [data-pointer data-type]} (nth class-table (- type-id c/VU_LAST_ENTITY_TYPE))]
    (nth (data-table database data-type) data-pointer)))

(defn unit-name
  "Returns a human-readable name for the given unit"
  [unit database]
  (let [{:keys [strings]} database
        {:keys [name-id type-id]} unit
        {:keys [name]} (class-data database type-id)
        {:keys [domain type] :as ci} (class-info database type-id)]
    ;; Ref: unit.cpp::GetName
    (condp = [domain type]
      [c/DOMAIN_AIR c/TYPE_FLIGHT]
      (let [{:keys [callsign-id callsign-num]} unit]
        (str (strings (+ c/FIRST_CALLSIGN_ID callsign-id))
             " "
             callsign-num))

      [c/DOMAIN_AIR c/TYPE_PACKAGE]
      (let [{:keys [camp-id]} unit]
        (str "Package " camp-id))

      (let [{:keys [name-id]} unit]
        (str name-id
             (ordinal-suffix name-id database)
             " "
             name
             " "
             (get-size-name unit database))))))

(defn unit-lookup-by-id
  "Given a seq of units, return the one with the given id."
  [units id]
  (some (fn [unit]
          (when (= id (:id unit))
            unit))
        units))

(defmethod read-embedded-file* :units
  ;; Ref: UniFile.cs, units.cpp
  [_
   {:keys [offset length] :as entry}
   buf
   {:keys [class-table] :as database}]
  (binding [octet.buffer/*byte-order* :little-endian]
    (let [header-spec (buf/spec :compressed-size buf/int32
                                :num-units buf/int16
                                :uncompressed-size buf/int32)
          {:keys [compressed-size
                  num-units
                  uncompressed-size]} (buf/read buf
                                                header-spec
                                                {:offset offset})
          data (lzss/expand buf
                            (+ offset (buf/size header-spec))
                            (- length 6)
                            uncompressed-size)]
      ;; Oddly, there can be entries in the table where the unit type
      ;; is zero. That'll return a nil unit when read, which we throw
      ;; away.
      #_(log/debug "read-embedded-file* (:units)"
                   :num-units num-units
                   :compressed-size compressed-size
                   :uncompressed-size uncompressed-size)
      (->> (buf/read
            data
            (buf/repeat num-units (unit-record database)))
           (remove nil?)
           (mapv (fn [unit]
                   (assoc unit :name (unit-name unit database))))))))

(defmethod read-embedded-file* :default
  [_ entry buf _]
  :not-yet-implemented)

(defmulti stringify*
  "Turns a value into something suitable for display, based on its
  context identifier."
  (fn [database context value] context))

(defmethod stringify* :flight-mission
  [mission _ value]
  (let [strings (-> mission :strings)]
    (strings (+ 300 value))))

(defmethod stringify* :team-name
  [mission _ value]
  (-> mission :teams (nth value) :team :name))

(defmethod stringify* :package-name
  [mission _ package-id]
  (let [packages (->> mission
                      :units
                      (util/filter= :type :package))]
    (->> package-id
         (unit-lookup-by-id packages)
         :name-id
         str)))

;; This doesn't do well with carrier airbase names. For that, we need
;; to somehow figure out how to chase our way into the vehicle info
;; and get the carrier vehicle name, which is where we get things
;; like "CVN70 Vinson". Airbase unit names for carrier are things
;; like "Carrier 7"
(defmethod stringify* :airbase-name
  [mission _ airbase]
  (let [names (:names mission)]
    (-> airbase :name-id names)))

(defmethod stringify* :default
  [_ _ value]
  value)

;; Always wrap a multimethod with a function so we have a single point
;; of entry.
(defn stringify
  "Return a human-readable version of some coded quantity."
  [mission context value]
  (stringify* mission context value))

(defn squadron-airbase
  "Returns the airbase objective for the given squadron."
  [mission squadron]
  (let [squadron-id (-> squadron :id :name)
        unit (->> mission :units (util/filter= :camp-id squadron-id) util/only)
        airbase-id (:airbase-id unit)]
    (->> mission :objectives (util/filter= :id airbase-id) util/only)))

(defn objective-name
  "Returns the name of the given objective."
  [mission objective]
  (let [names (:names mission)]
    (-> mission :name-id names)))

;; Note that the below will also return carriers, which might be what
;; we want, but might not be. Carrier airbases have subtype 7 and
;; specific type 7.
;;
;; Made this private because it's confusing that it doens't return
;; squadrons etc. Should prefer the OOB functions, which do.
(defn- airbases
  "Returns all the airbase and airstrip objectives."
  [mission]
  (let [airbase-classes (->> mission
                             :class-table
                             (util/filter= #(get-in % [:vu-class-data :class-info :domain])
                                           c/DOMAIN_LAND)
                             (util/filter= #(get-in % [:vu-class-data :class-info :class])
                                           c/CLASS_OBJECTIVE)
                             (filter #(#{c/TYPE_AIRBASE c/TYPE_AIRSTRIP}
                                       (get-in % [:vu-class-data :class-info :type])))
                             (map ::index)
                             set)]
    (->> mission
         :objectives
         (filterv (fn [objective]
                    (airbase-classes (- (:entity-type objective) 100)))))))
(defn side
  "Returns the side given a team. Team indicates the individual
  combatant, side denots the alliance."
  [mission team]
  (-> mission :teams (nth team) :team :c-team))

;; These are abstract, not particular RGB colors
(def team-color
  {0 :white
   1 :green
   2 :blue
   3 :brown
   4 :orange
   5 :yellow
   6 :red
   7 :grey})

(defn squadron-aircraft
  "Returns a map of `:airframe` and `:quantity` for a squadron."
  [mission squadron]
  {:airframe (-> squadron
                     :type-id
                     (- 100)
                     (->> (nth (:class-table mission)))
                     :data-pointer
                     (->> (nth (:unit-class-data mission)))
                     :vehicle-type
                     first
                     (->> (nth (:class-table mission)))
                     :data-pointer
                     (->> (nth (:vehicle-class-data mission)))
                     :name)
   :quantity (reduce +
                     (for [i (range 16)]
                       (-> squadron
                           :roster
                           (bit-shift-right (* i 2))
                           (bit-and 0x03))))})

;; Status algorithm at objectiv.cpp(2455)
(defn airbase-status
  "Return the status of an airbase as a number from 0 to 100."
  [mission airbase]
  ;; For each non-runway feature
  ;; - Start with 100.
  ;; - Subtract half the feature value [objective.cpp(2396)] if it's
  ;;   damaged, and the full value if it's destroyed.
  ;; - This number is the status
  ;; For each runway feature
  ;; - For each entry in the point data
  ;;   - If it is a runway point, increment the runway count. If it's
  ;;     also destroyed, increment the inactive account.
  ;; Use the lower of the status and the percentage of active runways
  (let [{:keys [class-table
                objective-class-data
                feature-class-data
                feature-entry-data
                point-header-data]} mission
        airbase-class (-> airbase
                          :entity-type
                          (- 100)
                          (->> (nth class-table))
                          :data-pointer
                          (->> (nth objective-class-data)))
        {:keys [features first-feature]} airbase-class
        feature-info (map #(nth feature-entry-data %)
                          (range first-feature (+ first-feature features)))
        feature-status (for [f (range features)]
                         (let [i (-> f (/ 4) long)
                               f* (- f (* i 4))]
                           (if (or (neg? f*) (< 255 f*))
                             0
                             (-> airbase
                                 :f-status
                                 (nth i)
                                 (bit-shift-right (* f* 2))
                                 (bit-and 0x03)))))
        feature-class-info (map (fn [feature]
                                  (let [ci (-> feature
                                               :index
                                               (->> (nth class-table)))]
                                    (assoc ci
                                           :feature-class-info
                                           (-> ci
                                               :data-pointer
                                               (->> (nth feature-class-data))))))
                                feature-info)
        feature-info (map (fn [fi fs fci]
                            (assoc fi
                                   :status fs
                                   :class-info fci
                                   ))
                          feature-info
                          feature-status
                          feature-class-info)
        runway? (fn [feature]
                  (-> feature
                      :class-info
                      :vu-class-data
                      :class-info
                      :type
                      (= c/TYPE_RUNWAY)))
        nonrunway-statuses (->> feature-info
                                (remove runway?)
                                (reduce (fn [score feature]
                                          (+ score
                                             (condp = (:status feature)
                                               c/VIS_DAMAGED (-> feature
                                                                 :value
                                                                 (/ 2)
                                                                 long)
                                               c/VIS_DESTROYED (:value feature)
                                               0)))
                                        0)
                                (- 100)
                                (max 0))
        ;; The point header data contains information about a "logical
        ;; runway". That is, what we would think of as a runway. These
        ;; reference runway features. Status of a runway is the worst status
        ;; of any of its features. Overall status is the worse of nonrunway
        ;; and runway status.
        runway-statuses (loop [index (:pt-data-index airbase-class)
                               runway-stats {:total 0
                                             :destroyed 0}]
                          (let [point-header (nth point-header-data index)]
                            (cond
                              (zero? index)
                              runway-stats

                              :else
                              (recur (:next-header point-header)
                                     (if-not (= c/RunwayPt (:type point-header))
                                       runway-stats
                                       (-> runway-stats
                                           (update :total inc)
                                           (update :destroyed
                                                   +
                                                   (if (->> point-header
                                                            :features
                                                            (take-while #(< % 255))
                                                            (map #(nth feature-info %))
                                                            (map :status)
                                                            (some #{c/VIS_DESTROYED}))
                                                     1
                                                     0))))))))
        {:keys [total destroyed]} runway-statuses
        runway-score (if (zero? total)
                       100
                       (->> (/ destroyed total)
                            (* 100)
                            long
                            (- 100)))]
    (min runway-score nonrunway-statuses)))

(defn oob-air
  "Returns a seq of airbases."
  [mission]
  (let [squadrons (->> mission
                       :units
                       (group-by :type)
                       :squadron
                       (map (fn [squadron]
                              (assoc squadron
                                     ::aircraft (squadron-aircraft mission squadron))))
                       (group-by :airbase-id))]

    (->> mission
         airbases
         (map (fn [airbase]
                (assoc airbase
                       ::squadrons (get squadrons (:id airbase))
                       ::status (airbase-status mission airbase)))))))

(defn order-of-battle
  "Returns the order of battle, a map from category (air,
  army, naval, objective) to entries (specific to category)."
  [mission]
  ;; TODO: Group by side, category
  ;; TODO: Add army, navy, objective
  {:air (oob-air mission)})

(defn objective-class
  [mission objective]
  (-> objective
      :entity-type
      (- 100)
      (->> (nth (:class-table mission)))
      :data-pointer
      (->> (nth (:objective-class-data mission)))))


;; Bunch of ideas:
;;
;; Things like having a name or being able to be turned into a string
;; should be protocols. The read functions should therefore return
;; records for something like airbases, squadarons, etc.
;;
;; Looks like the way we know how many of each aircraft are in a

;; squadron is to check the vehicle types of the squadron class but
;; DON'T subtract 100 like usual when indexing into the class table.
;; Then you use the roster on the unit instance somehow - it looks
;; like maybe there are two bits per vehicle group showing the count.
;; This implies that we should deserialize that field as a vector.