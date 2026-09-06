(ns ipni.metadata
  "IPNI advertisement metadata — a multicodec protocol identifier, then
  protocol-specific bytes.

  kotobase retrieval is `transport-ipfs-gateway-http` (`0x0920`). That is
  what kotoba `codebase-routing` already filters for. Bitswap (`0x0900`)
  is here because IPNI talks it; kotobase does not.

  Encoding is unsigned LEB128. This namespace does not import
  io-multiformats: the identifier is one varint, not a CID.")

(def ^:const bitswap 0x0900)
(def ^:const graphsync-filecoin 0x0909)
(def ^:const graphsync 0x0910)
(def ^:const http 0x0911)
(def ^:const gateway-http 0x0920)


;; ── IPQ: bounded, verifiable selection over HTTP ─────────────────────────────
;;
;; What kotobase can already say through IPNI is `transport-ipfs-gateway-http`:
;; "ask me for a CID and I will hand you the bytes". What it cannot say is the
;; thing it actually does -- run a bounded IPLD selector and return exactly the
;; blocks that prove the result. There is no registered protocol identifier for
;; that, so a provider offering it is indistinguishable from one that does not.
;;
;; Verified against the multicodec table on 2026-09-06 before choosing a code:
;;
;;   - README "Reserved Code Ranges / Private Use Area" gives 0x300000-0x3FFFFF,
;;     "reserved for internal use by applications and will never be assigned any
;;     meaning as part of the Multicodec specification".
;;   - table.csv carries ZERO entries in that range, and none at 0x0940.
;;   - the registered transport family is spaced by 0x10: 0x0900 bitswap,
;;     0x0910 graphsync-filecoinv1, 0x0920 ipfs-gateway-http,
;;     0x0930 filecoin-piece-http. 0x0940 is the next free slot.
;;
;; So the code below is the private-use base plus the slot a registration would
;; ask for. Announcing an unregistered code OUTSIDE the private range would be
;; squatting on a registry we do not own; announcing one inside it is what the
;; range is for, and it is self-describing -- a reader that does not know this
;; code learns that it is application-private, not that the advertisement is
;; malformed.
(def ^:const ipq-selection-http 0x300940)

(def ^:const ipq-selection-http-registration-request
  "The code a multicodec registration would ask for. Not announced. Recorded so
  the ask and the private-use code cannot drift apart."
  0x0940)

;; The profile number is where the honesty lives. IPQ/1 proves that a named
;; traversal was executed against CID-verified blocks -- it does NOT prove that
;; a database range or a Datalog answer is complete, which needs authenticated
;; index boundaries (kotobase ADR-2609060000). A later profile that carries
;; those may exist; naming the layer IPQ does not backdate it.
(def ^:const ipq-selection-profile 1)
(def supported-ipq-profiles #{1})

(def protocol-name
  {bitswap "transport-bitswap"
   graphsync-filecoin "transport-graphsync-filecoinv1"
   graphsync "transport-graphsync"
   http "transport-http"
   gateway-http "transport-ipfs-gateway-http"
   ipq-selection-http "transport-ipq-selection-http"})

(defn uvarint-encode
  "Unsigned LEB128. Refuses negatives rather than wrapping."
  [n]
  (cond
    (not (integer? n)) {:error :not-integer :value n}
    (neg? n) {:error :negative :value n}
    :else
    (loop [n n out []]
      (if (< n 128)
        (conj out n)
        (recur (quot n 128)
               (conj out (bit-or (bit-and n 0x7F) 0x80)))))))

(defn uvarint-decode
  "First unsigned LEB128 in `octets`. Returns `{:value :rest}` or an error.
  An empty input is not 0 — silence is not a protocol identifier."
  [octets]
  (let [xs (vec octets)]
    (if (empty? xs)
      {:error :empty}
      (loop [i 0 acc 0 shift 0]
        (if (>= i (count xs))
          {:error :truncated}
          (let [b (bit-and (nth xs i) 0xFF)
                acc (bit-or acc (bit-shift-left (bit-and b 0x7F) shift))]
            (if (zero? (bit-and b 0x80))
              {:value acc :rest (subvec xs (inc i))}
              (if (> shift 63)
                {:error :overflow}
                (recur (inc i) acc (+ shift 7))))))))))

(defn encode
  "Protocol identifier as metadata bytes. Extra protocol bytes may follow."
  ([protocol] (encode protocol nil))
  ([protocol extra]
   (let [head (uvarint-encode protocol)]
     (if (:error head)
       head
       (into head (mapv #(bit-and % 0xFF) (or extra [])))))))

(defn decode
  [octets]
  (let [r (uvarint-decode octets)]
    (if (:error r)
      r
      {:protocol (:value r)
       :name (get protocol-name (:value r))
       :extra (:rest r)})))

(defn gateway-http-bytes
  "Metadata kotobase advertisements carry. Retrieval is HTTPS gateway,
  not Bitswap."
  []
  (encode gateway-http))

(defn gateway-http?
  [octets]
  (= gateway-http (:protocol (decode octets))))

(defn ipq-selection-http-bytes
  "Metadata for an IPQ selection endpoint: protocol identifier, then one
  uvarint profile number. Nothing else -- the endpoint's own budgets are read
  from the endpoint, not guessed from an advertisement that may be days old."
  ([] (ipq-selection-http-bytes ipq-selection-profile))
  ([profile]
   (let [head (uvarint-encode ipq-selection-http)]
     (if (:error head)
       head
       (let [tail (uvarint-encode profile)]
         (if (:error tail) tail (into head tail)))))))

(defn read-ipq
  "Read IPQ metadata, keeping three answers apart that a predicate would fuse.

  Returns `{:ok? true :protocol :profile}`, or `{:ok? false :reason ...}` with

      :not-ipq              some other transport, or undecodable
      :profile-missing      our identifier with no profile number after it
      :profile-unsupported  our identifier, a profile we do not implement

  There is deliberately no `ipq?` predicate. A boolean makes the third case
  read as the first, and those are opposite instructions: `:not-ipq` means
  route elsewhere, `:profile-unsupported` means this provider speaks a newer
  IPQ than we do and a caller that treats it as \"not IPQ\" will never notice
  the version it is failing to keep up with."
  [octets]
  (let [d (decode octets)]
    (cond
      (:error d) {:ok? false :reason :not-ipq :detail (:error d)}
      (not= ipq-selection-http (:protocol d)) {:ok? false :reason :not-ipq
                                               :protocol (:protocol d)}
      :else
      (let [p (uvarint-decode (:extra d))]
        (cond
          (:error p) {:ok? false :reason :profile-missing :detail (:error p)}
          (not (contains? supported-ipq-profiles (:value p)))
          {:ok? false :reason :profile-unsupported :profile (:value p)
           :supported supported-ipq-profiles}
          :else {:ok? true :protocol ipq-selection-http :profile (:value p)})))))
