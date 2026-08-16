(ns ipni.announce
  "Announce message: advertisement CID + publisher addresses.

  The indexer fetches `GET /ipni/v1/ad/{ad-cid}` from those addresses.
  They are not retrieval multiaddrs. Mixing the two is how a gateway
  URL gets announced as a publisher and the indexer 404s the chain.

  `:ad-cid` is the advertisement CID. `:cid` on a discover record is the
  content CID. This map uses `:ad-cid` so they cannot be swapped by
  accident. The JSON field the indexer reads is still `cid`.")

(defn message
  [{:keys [ad-cid addrs cid]}]
  (cond
    (and (string? cid) (not (string? ad-cid)))
    {:error :content-cid-is-not-an-advertisement
     :hint "pass :ad-cid (advertisement CID), not the content CID"
     :value cid}
    (not (string? ad-cid)) {:error :invalid-ad-cid :value ad-cid}
    (not (sequential? addrs)) {:error :invalid-addrs :value addrs}
    (empty? addrs) {:error :empty-publisher-addrs}
    :else
    {:ad-cid ad-cid
     :addrs (vec addrs)
     :wire {:cid ad-cid :addrs (vec addrs)}}))
