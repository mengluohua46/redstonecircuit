package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHostCheck;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;

/**
 * The two frame-summary rows, built from key/value pairs rather than hand-written strings.
 *
 * <p>The keys are data: {@link LogKeys} refuses a repeated key, so the wording collision two places
 * produce when they write the same name cannot be constructed. The default row writes exactly
 * {@link #WHITELIST_KEYS}; the verbose row adds the counters and the other groups. Sentences rather than
 * counts - the {@code [gates]} group, the geometry verdict - ride as heads or tails, never as keys.
 */
final class CarrierFrameRow {

    /**
     * The default row's key names as constants, so the builder and the test cannot spell a key two ways.
     */
    static final class Keys {
        static final String POSE_AGE = "poseAge";
        static final String CARRIERS = "carriers";
        static final String CARRIED_HOSTS = "carriedHosts";
        static final String LANDED_HOSTS = "landedHosts";
        static final String FALLING_BACK = "fallingBack";
        static final String SIGHT_CHECKED = "sightChecked";
        static final String SIGHT_DRAWN = "sightDrawn";
        static final String SIGHT_REFUSED_HIT = "sightRefusedHit";
        static final String SIGHT_MISS = "sightMiss";
        static final String SIGHT_UNKNOWN_TARGET = "sightUnknownTarget";

        private Keys() {
        }
    }

    /**
     * Every key the default row writes, in the order it writes them. The head ({@code frame drew N}) and
     * the tail (the geometry verdict) are sentences, not keys: the gates group already carries a
     * {@code frame=} of its own, and a reader counting fields would see that name twice on one line.
     */
    static final List<String> WHITELIST_KEYS = List.of(
            Keys.POSE_AGE,
            Keys.SIGHT_CHECKED,
            Keys.SIGHT_DRAWN,
            Keys.SIGHT_REFUSED_HIT,
            Keys.SIGHT_MISS,
            Keys.SIGHT_UNKNOWN_TARGET,
            Keys.CARRIERS,
            Keys.CARRIED_HOSTS,
            Keys.LANDED_HOSTS,
            Keys.FALLING_BACK);

    /**
     * The per-frame counters that ride on the verbose row only: {@code CarrierTrace.Render#COUNTERS}
     * itself rather than a copy, so a counter added there reaches this row and the disjointness check
     * without anybody having to remember to add it here.
     */
    static final List<String> COUNTER_KEYS = CarrierTrace.Render.COUNTERS;

    /**
     * The default row and the counter group share no key: checked at class initialisation rather than only
     * in a test, because it is a property of the two lists above, and a move between them must fail loudly.
     */
    private static final Set<String> OVERLAP = overlap();

    private static Set<String> overlap() {
        Set<String> both = new LinkedHashSet<>(WHITELIST_KEYS);
        both.retainAll(COUNTER_KEYS);
        if (!both.isEmpty()) {
            throw new IllegalStateException("the default frame row and the frame counters must not share "
                    + "a key, or one of them writes it twice: " + both);
        }
        return Set.of();
    }

    private CarrierFrameRow() {
    }

    /** The intersection of the two key sets; always empty, by the check above. */
    static Set<String> overlappingKeys() {
        return OVERLAP;
    }

    /**
     * One line's keys and values, which refuses to write the same key twice: a duplicate is a value a
     * reader never sees and a count a test cannot explain. Groups whose keys belong to another class go
     * through {@link #addAllIfAbsent}, which drops the later writer instead of throwing.
     */
    static final class LogKeys {

        private final Map<String, String> values = new LinkedHashMap<>();

        /**
         * Sentences that ride after the pairs: the {@code [gates]} group and the geometry verdict. Kept
         * out of the map, because a word containing an {@code =} would otherwise become a key.
         */
        private final List<String> tails = new ArrayList<>();

        /**
         * The head: one sentence before every pair, the phrase a reader greps the line by, which must come
         * first and must not be a key.
         */
        private final List<String> heads = new ArrayList<>();

        /** Adds the sentence that comes before the pairs; an empty or {@code null} one is not added. */
        LogKeys addHead(String head) {
            if (head != null && !head.isBlank()) {
                heads.add(head.trim());
            }
            return this;
        }

        /** Adds one key and its value; throws when the key is already here. */
        LogKeys add(String key, String value) {
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("the key '" + key + "' is already on this line: "
                        + values.keySet() + " - two fields of the same name make the line, and any count "
                        + "taken from it, unreadable; this is the defect the row builder exists to make "
                        + "impossible");
            }
            return this;
        }

        /**
         * Adds a key only when the line does not already carry it, for the groups this class does not own:
         * the first writer of a name wins and later ones are dropped.
         */
        boolean addIfAbsent(String key, String value) {
            return values.putIfAbsent(key, value) == null;
        }

        /**
         * Adds every pair of another class's already formatted group, skipping the names this line already
         * carries. The split is on {@code key=} rather than on whitespace, so a value is kept whole
         * however many spaces it contains.
         */
        int addAllIfAbsent(String group) {
            int added = 0;
            for (Map.Entry<String, String> pair : parse(group).entrySet()) {
                if (addIfAbsent(pair.getKey(), pair.getValue())) {
                    added++;
                }
            }
            return added;
        }

        /** Appends a sentence after the pairs; an empty or {@code null} one is not appended at all. */
        LogKeys addTail(String tail) {
            if (tail != null && !tail.isBlank()) {
                tails.add(tail.trim());
            }
            return this;
        }

        /** The keys of this line, in the order they were added. */
        Set<String> keys() {
            return values.keySet();
        }

        /** Whether this line carries a key at all. */
        boolean has(String key) {
            return values.containsKey(key);
        }

        /** The line: the head sentence, then every pair in insertion order, then the sentences. */
        String asLine() {
            StringBuilder sb = new StringBuilder();
            for (String head : heads) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(head);
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            for (String tail : tails) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tail);
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return asLine();
        }
    }

    /** The whole row for one frame; the entry point both the production call site and the test use. */
    static String render(Frame frame, boolean verbose) {
        LogKeys keys = build(frame, verbose);
        // The verdict rides on both rows: a frame that drew nothing reports it without the debug switch.
        keys.addTail(frame.verdict());
        return keys.asLine();
    }

    /**
     * The same row with its keys still separated from the line, so a test can count fields rather than
     * characters; {@link #render} builds the line from this same set.
     */
    static LogKeys build(Frame frame, boolean verbose) {
        LogKeys keys = new LogKeys();
        addDefaultKeys(keys, frame);
        if (verbose) {
            addVerboseKeys(keys, frame);
        }
        return keys;
    }

    /** The default row's keys, in reading order; every value comes from the record, so the row is pure. */
    private static void addDefaultKeys(LogKeys keys, Frame frame) {
        // The head: a sentence rather than a key, with the comma that makes the next pair part of it.
        keys.addHead(frame.framePhrase() + ",");
        keys.add(Keys.POSE_AGE, frame.poseAgePhrase());
        keys.add(Keys.SIGHT_CHECKED, Integer.toString(frame.sight().checked()));
        keys.add(Keys.SIGHT_DRAWN, Integer.toString(frame.sight().drawn()));
        keys.add(Keys.SIGHT_REFUSED_HIT, Integer.toString(frame.sight().refused()));
        keys.add(Keys.SIGHT_MISS, Integer.toString(frame.sight().miss()));
        keys.add(Keys.SIGHT_UNKNOWN_TARGET, Integer.toString(frame.sight().unknownTarget()));
        keys.add(Keys.CARRIERS, Integer.toString(frame.carriers()));
        keys.add(Keys.CARRIED_HOSTS, Integer.toString(frame.carriedHosts()));
        keys.add(Keys.LANDED_HOSTS, Integer.toString(frame.landedHosts()));
        keys.add(Keys.FALLING_BACK, Integer.toString(frame.fallingBack()));
    }

    /**
     * The verbose row's extra keys: the gates group, the counters, the flush stages and the compact group,
     * in the order the line has always had.
     */
    private static void addVerboseKeys(LogKeys keys, Frame frame) {
        String gates = frame.gates() == null || frame.gates().isEmpty() ? "-" : frame.gates();
        // The gates group is carried whole, not parsed: its inactive reason holds a sentence with spaces.
        keys.addTail("[" + gates + "]");
        keys.addAllIfAbsent(frame.frameCounters());
        // The flush stage is one value rather than a group of pairs, so no second counter can hide in it.
        keys.addIfAbsent("flushStages", frame.flushStages());
        keys.addAllIfAbsent(frame.renderCompact());
    }

    /**
     * One frame, as the rows describe it. {@code worstPoseAgeTicks} is the age of the oldest pose drawn
     * from, or {@code -1}; {@code gates} is the switches the frame was drawn under.
     */
    record Frame(int drawn, int skippedNoPose, int skippedFar, int skippedStalePose,
                 long worstPoseAgeTicks, CarrierHostCheck.Tally sight, int carriers, int carriedHosts,
                 int landedHosts, int fallingBack, int shellQuads, int contentQuads, int hostsDrawn,
                 String gates, String flushStages, String frameCounters, String renderCompact,
                 String verdict) {

        /** The frame and the client's own counts, read from the two records that hold them. */
        static Frame of(CarrierTrace.Render render, int drawn, int skippedNoPose, int skippedFar,
                        int skippedStalePose, long worstPoseAgeTicks) {
            return new Frame(drawn, skippedNoPose, skippedFar, skippedStalePose, worstPoseAgeTicks,
                    render.sight(), ClientHostCache.carrierCount(), ClientHostCache.carriedSize(),
                    ClientHostCache.landedCount(), ClientHostCache.fallbackCount(),
                    render.shellQuads(), render.contentQuads(), render.hostsDrawn(), render.gates(),
                    render.flushStages(), render.frameCounters(),
                    render.describeWithoutFrameCounters(), render.geometryVerdict());
        }

        /** {@code frame drew N}, plus the skip group only when a skip count is nonzero. */
        String framePhrase() {
            StringBuilder sb = new StringBuilder("frame drew ").append(drawn);
            if (skippedNoPose != 0 || skippedStalePose != 0 || skippedFar != 0) {
                // Only when nonzero: three constant `=0` pairs on every line are pure weight.
                sb.append(" skipped(noPose=").append(skippedNoPose)
                        .append(" stale=").append(skippedStalePose)
                        .append(" far=").append(skippedFar).append(')');
            }
            return sb.toString();
        }

        /**
         * Always written, so a reader can tell "this build does not report ages" from "this frame drew
         * from a fresh pose".
         */
        String poseAgePhrase() {
            return CarrierRenderTrace.poseAgePhrase(worstPoseAgeTicks);
        }
    }

    /**
     * One field's name and its {@code =}, with nothing after it: a value is the gap between two matches,
     * because a capture group for it would swallow the next field's name. The lookbehind keeps a name from
     * being found in the middle of a token, so a hex colour does not yield a field of its own.
     */
    private static final Pattern FIELD_NAME = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*)=");

    /**
     * Splits a formatted {@code key=value …} group into its fields, each value running to the next
     * {@code name=} so that whatever spaces it contains stay in it. Inner names a value contains therefore
     * become extra fields: harmless, because the outer key was added by hand first.
     */
    private static Map<String, String> parse(String group) {
        Map<String, String> out = new LinkedHashMap<>();
        if (group == null || group.isBlank()) {
            return out;
        }
        String trimmed = group.trim();
        Matcher matcher = FIELD_NAME.matcher(trimmed);
        List<Integer> valueStarts = new ArrayList<>();
        List<Integer> nameStarts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            nameStarts.add(matcher.start());
            valueStarts.add(matcher.end());
            names.add(matcher.group(1));
        }
        for (int i = 0; i < names.size(); i++) {
            int to = i + 1 < names.size() ? nameStarts.get(i + 1) : trimmed.length();
            out.putIfAbsent(names.get(i), trimmed.substring(valueStarts.get(i), to).trim());
        }
        return out;
    }
}
