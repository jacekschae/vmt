(ns weathergen.falcon.files.images
  (:require [taoensso.timbre :as log
             :refer-macros (log trace debug info warn error fatal report
                                logf tracef debugf infof warnf errorf fatalf reportf
                                spy get-env log-env)]
            [octet.core :as buf]
            [weathergen.falcon.constants :as c]
            [weathergen.filesystem :as fs]))

(defn read-index
  [path]
  ;; (log/debug "read-index" :path path)
  (let [common-header (buf/spec
                       :type buf/int32
                       :id (buf/string 32))
        image-header  (buf/spec
                       :flags buf/int32
                       :center (buf/repeat 2 buf/int16)
                       :size (buf/repeat 2 buf/int16)
                       :image-offset buf/int32
                       :palette-size buf/int32
                       :palette-offset buf/int32)
        sound-header  (buf/spec
                       :flags buf/int32
                       :channels buf/int16
                       :sound-type buf/int16
                       :offset buf/int32
                       :header-size buf/int32)
        flat-header   (buf/spec
                       :offset buf/int32
                       :size   buf/int32)
        file-header   (buf/spec :size buf/int32
                                :version buf/int32)
        idx-buf       (fs/file-buf path)]
    (binding [octet.buffer/*byte-order* :little-endian]
      (let [{:keys [size]} (buf/read idx-buf file-header)]
        (loop [offset (buf/size file-header)
               items []]
          (if-not (-> size (- offset) pos?)
            items
            (let [[size common]    (buf/read* idx-buf common-header
                                              {:offset offset})
                  offset           (+ offset size)
                  [size specifics] (buf/read* idx-buf
                                              (condp = (:type common)
                                                c/_RSC_IS_IMAGE_  image-header
                                                c/_RSC_IS_SOUND_ sound-header
                                                c/_RSC_IS_FLAT_  flat-header)
                                              {:offset offset})]
              (recur (+ offset size)
                     (conj items (into common specifics))))))))))

(defn palette
  [image-buf image-data]
  (let [{:keys [palette-size palette-offset]} image-data]
    (binding [octet.buffer/*byte-order* :little-endian]
      (buf/read image-buf
                (buf/repeat palette-size buf/uint16)
                ;; Eight bytes accounts for the size and
                ;; version longs at the beginning of the
                ;; file.
                {:offset (+ palette-offset 8)}))))

;; The fact that we're packing argb into a single arg is a hack due to
;; the limitations of Clojure passing primitives - functions can't
;; have more than four primitive args
(defn read-image
  "Reads image described by `image-descriptor`. `allocator` is a
  function of a width and height. It returns a map of two functions.
  The function under `:set-pixel!` will be called with x, y, and argb
  for each pixel in the image, where argb is a long in 0xAABBGGRR
  format. The function under the `:finalize` key will be called with
  no arguments after all pixels are set. `read-image` returns the
  result of calling the finalize function."
  [mission image-descriptor allocator]
  ;; TODO: Try theater directory first and fall back to Falcon dir
  (let [{:keys [resource image-id]} image-descriptor
        data-dir (-> mission :installation :data-dir)
        theater-resource (fs/path-combine data-dir
                                          (-> mission :theater :art-dir)
                                          resource)
        install-resource (fs/path-combine data-dir
                                          (-> mission :installation :art-dir)
                                          resource)
        _ (log/debug "read-image"
                     :image-descriptor image-descriptor
                     :theater-resource theater-resource
                     :install-resource install-resource)
        resource-base (if (fs/exists? (str theater-resource ".idx"))
                        theater-resource
                        install-resource)
        index (read-index (str resource-base ".idx"))
        image-buf (fs/file-buf (str resource-base ".rsc"))
        image-data  (->> index (filter #(= image-id (:id %))) first)
        _ (assert image-data (with-out-str (print "Couldn't find an image with that ID" :resource resource :image-id image-id)))
        {:keys [palette-size palette-offset center image-offset size]} image-data
        palette (palette image-buf image-data)
        [width height] size
        is-8-bit? (-> image-data :flags (bit-and c/_RSC_8_BIT_) pos?)
        use-transparency? (-> image-data :flags (bit-and c/_RSC_USECOLORKEY_) pos?)]
    (when-not (-> image-data :type (= c/_RSC_IS_IMAGE_))
      (throw (ex-info "Not an image" {:reason ::not-an-image})))
    (when (and (not is-8-bit?) (not (-> image-data :flags (bit-and c/_RSC_16_BIT_) pos?)))
      (throw (ex-info "Image is neither 8-bit nor 16-bit" {:reason ::bit-size-unknown})))
    (let [{:keys [set-pixel! finalize]} (allocator width height)
          pixel-bytes (if is-8-bit? 1 2)
          read-pixel (if is-8-bit?
                       octet.buffer/read-ubyte
                       octet.buffer/read-ushort)]
      (binding [octet.buffer/*byte-order* :little-endian]
        ;; TODO: Take out the limits
        (doseq [y (range height)
                x (range width)]
          (let [offset (+ 8 image-offset (* pixel-bytes (+ x (* y width))))
                c (read-pixel image-buf offset)
                raw ^long (if is-8-bit? (nth palette c) c)
                r ^long (-> raw (bit-shift-right 10) (bit-and 0x1f))
                g ^long (-> raw (bit-shift-right 5)  (bit-and 0x1f))
                b ^long (-> raw                      (bit-and 0x1f))
                transparent? (and use-transparency?
                                  (if is-8-bit?
                                    (zero? c)
                                    (= [31 0 31] [r g b])))
                argb ^long (-> (if transparent? 0 255)
                               (bit-shift-left 8)
                               (+ (* b 8))
                               (bit-shift-left 8)
                               (+ (* g 8))
                               (bit-shift-left 8)
                               (+ (* r 8)))]
            (set-pixel! x y argb))))
      (finalize))))
