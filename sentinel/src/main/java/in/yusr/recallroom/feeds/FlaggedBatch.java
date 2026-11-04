package in.yusr.recallroom.feeds;

/**
 * One batch a regulator has flagged, whichever feed it came from.
 *
 * @param source    which feed and list, e.g. "CDSCO NSQ Sep-2026" or "openFDA D-0079-2027 (Class II)"
 * @param reference link or recall number a pharmacist can check the alert against
 */
public record FlaggedBatch(
        String source,
        String product,
        String batch,
        String expiry,
        String manufacturer,
        String reason,
        String reference) {

    public String key() {
        return BatchKeys.strict(batch);
    }
}
