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
 * <b>The two frame-summary rows, built from a list of keys and values rather than from hand-written
 * strings.</b>
 *
 * <h2>The defect this class exists to close, in one sentence</h2>
 * The previous two rounds of this work both failed the same way: a test asserted that a counter appeared
 * <b>exactly once</b> on a line, and the line wrote it <b>twice</b>. The default row wrote
 * {@code hostsDrawn=1of1} and the verbose row wrote the same name again inside
 * {@code CarrierTrace.Render#frameCounters()}; the default row wrote {@code skipped(…)} while the counter
 * group wrote {@code skippedNoPose=…}. Every one of those was a <em>wording</em> collision between two
 * pieces of text assembled in two places, and no amount of care at either place can find it, because
 * neither place can see the other's keys.
 *
 * <p>So the keys are now <b>data</b>. {@link LogKeys} holds key/value pairs and <b>refuses a repeated
 * key</b>, and both rows are built by handing it their keys in order. A collision is then not something a
 * test has to notice: it cannot be constructed. The test asserts the two consequences a reader cares
 * about - each row writes each of its keys once, and the default row's keys and the frame counters' keys
 * are disjoint - and the disjointness is checked here too, on the way in, so a future round that moves a
 * key from one group to the other is told immediately rather than at the first red assertion.
 *
 * <h2>The default row is exactly these keys, and no others</h2>
 * <pre>
 *   frame drew N [, skipped(noPose=… stale=… far=…)]   the group only when a skip count is nonzero
 *   poseAge=…
 *   sightChecked=… sightDrawn=… sightRefusedHit=… sightMiss=… sightUnknownTarget=…
 *   carriers=… carriedHosts=… landedHosts=… fallingBack=…
 * </pre>
 *
 * <p>…plus one optional <b>sentence</b> after the last pair: the geometry verdict
 * ({@code *** NOTHING EMITTED … ***}), which is empty on a healthy frame. A sentence is not a key, so it
 * cannot collide with one; see {@link LogKeys#addTail}.
 *
 * <p><b>Deliberately absent</b>: {@code drawnLastFrame}, {@code shellQuads}, {@code contentQuads},
 * {@code hostsDrawn}, the three skip counters, the four landed counters and the two draw-check counters.
 * Those are the per-frame detail that made the line 1-2 KB; they are written by the verbose shape from
 * {@code CarrierTrace.Render#frameCounters()}, which is the same spelling {@code /rc carrier} prints.
 *
 * <h2>The verbose row is the default row plus everything else</h2>
 * In order: the default keys, the {@code [gates]} group as a carried sentence, the 13 counters of
 * {@code frameCounters()}, {@code flushStages}, {@code describeWithoutFrameCounters()}, and finally the
 * verdict. It deliberately does <b>not</b> append {@code Render#describe()}, which writes
 * {@code shellQuads} and {@code contentQuads} a second time - that was the previous round's failure, one
 * line further down. And {@code describeWithoutFrameCounters()} no longer writes {@code poseAge},
 * {@code fallingBack}, {@code carriers} or {@code landedHosts}: all four are on the default row already,
 * and the row builder would find the collision.
 *
 * <h2>Nothing that is information was dropped to make the key set clean</h2>
 * The gates group is the example. Its keys belong to {@code CarrierGeometry.Gates}, and one of its
 * sentences ({@code hostOverlayMode=GOGGLES and the player is NOT wearing the redstone goggles}) contains
 * spaces and no key of its own - so it is carried <b>whole</b>, as a sentence, rather than parsed into
 * keys that would cut it in half. A row that is clean because the awkward text was deleted is not clean;
 * it is shorter.
 *
 * <h2>Cost</h2>
 * One {@link LogKeys} - a small {@code LinkedHashMap} - per written line: at most once per
 * {@code CarrierRenderTrace.QUIET_LOG_INTERVAL_TICKS} ticks per dimension in the default mode, and once
 * per frame in the verbose one. {@link #render} builds nothing unless the caller is already going to
 * write the line, and every method here is pure: it reads its arguments and nothing else.
 */
final class CarrierFrameRow {

    /**
     * <b>The default row's key names, as data.</b>
     *
     * <p>Named constants rather than literals so the builder and the test cannot spell a key two ways,
     * and so "the default row and the counters share no key" is a statement about two lists of names
     * rather than about two format strings.
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
     * <b>Every key the default row writes, in the order it writes them.</b>
     *
     * <p><b>{@code frame drew N} is deliberately not here.</b> It is the row's <em>head</em> - a sentence
     * before the first pair - and not a key, for two reasons that cost this round a red test to find:
     *
     * <ul>
     *   <li>writing it as a key ({@code frame=frame drew N}) puts the words {@code frame} and {@code drew}
     *       into the key namespace, and the verbose row appends the {@code [gates]} sentence, which
     *       contains a real key called {@code frame=0xFF000000} (the shell's frame colour). A reader
     *       counting fields per name - which is exactly what a test does - then sees {@code frame} twice on
     *       one line, and the two have nothing to do with each other;</li>
     *   <li>a head that is a sentence cannot collide with a key at all, which is the same reasoning that
     *       carries the gates group and the geometry verdict as text rather than as pairs.</li>
     * </ul>
     *
     * <p>The geometry verdict is likewise <b>not</b> in this list, and is carried as the row's
     * <em>tail</em>: text after the last pair, always present, which is what the line has always had and
     * what the {@code *** NOTHING EMITTED ***} sentence needs.
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
     * <b>The 13 per-frame counters that ride on the verbose row only.</b>
     *
     * <p>Not a copy of a list but {@code CarrierTrace.Render#COUNTERS} itself: the names, their order
     * and the {@code frameCounters()} format string are all built from that one declaration, so a
     * counter added to the command's line reaches the verbose row (and the disjointness check below)
     * whether or not anybody remembers to add it here. The test asserts the two are the same list as
     * well, which keeps the statement true even if this ever became a copy.
     */
    static final List<String> COUNTER_KEYS = CarrierTrace.Render.COUNTERS;

    /**
     * <b>The constructive statement: the default row and the counter group share no key at all.</b>
     *
     * <p>Checked at class-initialisation rather than only in a test, because it is a property of the two
     * lists above. If a future round moves {@code poseAge} into {@code frameCounters()}, or brings
     * {@code hostsDrawn} back onto the default row, the exception below is thrown at the first call
     * rather than left for a test to find - which is the difference between "an invariant" and "a
     * coincidence that currently holds".
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
     * <b>One line's keys and values, which refuses to write the same key twice.</b>
     *
     * <h2>Why it throws rather than overwrites</h2>
     * A key that is silently overwritten is a value a reader never sees and an occurrence count a test
     * cannot explain; a key that throws is a defect found at the moment it is introduced. The two ways a
     * duplicate could legitimately arrive are the free-form {@code [gates]} group - whose keys belong to
     * {@code CarrierGeometry.Gates} and are not this class's to police - and a {@code frameCounters()}
     * string that a future round extends; both go through {@link #addAllIfAbsent}, so the strictness is
     * kept where it is meaningful and the resilience where it is necessary.
     */
    static final class LogKeys {

        private final Map<String, String> values = new LinkedHashMap<>();

        /**
         * Free-form text that rides after the pairs: the {@code [gates]} group and the geometry verdict.
         *
         * <p>Kept apart from the map on purpose. Both are sentences rather than counts - the gates carry
         * their own explanation when the overlay is off, and the verdict is the
         * {@code *** NOTHING EMITTED ***} warning - and neither may be parsed into keys, because a word
         * that happens to contain an {@code =} would become one. They are in the row, in order, after the
         * last pair; they are not part of its key set, which is what makes "each key once" a statement
         * that a sentence cannot break.
         */
        private final List<String> tails = new ArrayList<>();

        /**
         * The <b>head</b>: one sentence before every pair, and the same shape as a tail.
         *
         * <p>Added for {@code frame drew N}: the phrase a reader greps the line by, which must come first
         * and must not be a key. Keeping it in its own list rather than letting it be a key is what makes
         * "every key once" a statement a sentence cannot break - the {@code [gates]} group already carries
         * a {@code frame=} of its own, and a key of that name would be counted twice by any reader (or
         * test) walking the line's fields.
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
         * Adds a key only when the line does not already carry it.
         *
         * <p>For the groups whose keys this class does not own: the frame counters and the compact
         * {@code render: …} group. The first writer of a name wins and later ones are dropped, which is
         * what makes the row a set of keys rather than a concatenation of strings.
         *
         * @return whether the pair was added
         */
        boolean addIfAbsent(String key, String value) {
            return values.putIfAbsent(key, value) == null;
        }

        /**
         * Adds every {@code key=value} pair of an already formatted group, skipping the names this line
         * already carries.
         *
         * <p>Used for exactly two strings, both of which are <b>other classes'</b> spelling rather than
         * this class's: {@code CarrierTrace.Render#frameCounters()} and
         * {@code Render#describeWithoutFrameCounters()}. Folding them in by name, rather than re-typing
         * their keys here, is what keeps the "one spelling" rule that {@code /rc carrier} depends on.
         *
         * <p>The split is on {@code key=} rather than on whitespace, so a value is kept whole however many
         * spaces it contains - {@code code=0x8040FF40 left=1} is two pairs, and
         * {@code reason=the block is gone} is one. That matters because a value that got cut in half would
         * take the rest of the group with it.
         *
         * @param group the group, as {@code key=value key=value …}
         * @return how many pairs were actually added
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

    /**
     * The whole row for one frame, in one of its two shapes.
     *
     * <p>The single entry point both the production call site and the test use, so the line a test
     * asserts on is the line the log carries.
     *
     * @param frame the frame's numbers
     * @param verbose whether to append the per-frame detail; see the class javadoc
     * @return the row, without the trailing {@code :} the caller appends
     */
    static String render(Frame frame, boolean verbose) {
        LogKeys keys = build(frame, verbose);
        // The geometry verdict is the one sentence that rides on both rows, because it is the sentence a
        // zero has to carry: `drew=3` with no geometry is the author's report, and it must not need the
        // debug switch to be visible.
        keys.addTail(frame.verdict());
        return keys.asLine();
    }

    /**
     * The same, with the keys still separated from the line.
     *
     * <p>Exists so a test can assert the row's <b>key set</b> rather than a substring of its text: a
     * count of {@code "hostsDrawn="} in a string is a count of characters, while a count of keys in the
     * set the builder produced is a count of fields. The line is built by {@link #render} from this same
     * set, which is what makes the set and the text the same row by construction rather than by agreement.
     */
    static LogKeys build(Frame frame, boolean verbose) {
        LogKeys keys = new LogKeys();
        addDefaultKeys(keys, frame);
        if (verbose) {
            addVerboseKeys(keys, frame);
        }
        return keys;
    }

    /**
     * The default row's keys, in the order a reader reads them.
     *
     * <p>Every value comes from the record; nothing here reads a static or a config, so the row is a pure
     * function of the frame it describes.
     */
    private static void addDefaultKeys(LogKeys keys, Frame frame) {
        // THE HEAD: the phrase a reader greps for, followed by the comma that makes the next pair read as
        // part of the same sentence. A sentence, not a key - see WHITELIST_KEYS.
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
     * The verbose row's extra keys: the gates group, the 13 counters, the flush stages and the compact
     * {@code render: …} group.
     *
     * <p>The order is the order the line has always had, so a reader who knows the previous line can
     * still read this one left to right. The counters and the compact group are other classes' spellings
     * and are folded in <b>by name</b> - which is what stops the same name reaching the line twice - while
     * the gates group is carried whole as a sentence, because it is a sentence.
     */
    private static void addVerboseKeys(LogKeys keys, Frame frame) {
        String gates = frame.gates() == null || frame.gates().isEmpty() ? "-" : frame.gates();
        // THE [gates] GROUP IS CARRIED, NOT PARSED. It is `overlayMode=… goggles=… active=… …`, and it
        // belongs to `CarrierGeometry.Gates`, whose `inactiveReason` can put a sentence with spaces in it
        // in the middle of the string: `hostOverlayMode=GOGGLES and the player is NOT wearing the
        // redstone goggles (wearing=false)`. Splitting that on whitespace would cut the sentence in half
        // and drop the half that explains the switch, so the whole group rides as a tail - where, being
        // after every pair, it cannot collide with a key name either.
        keys.addTail("[" + gates + "]");
        keys.addAllIfAbsent(frame.frameCounters());
        // The flush stage is one value, not a group of pairs: `flushStages` is the key and the label its
        // value, so a caller cannot smuggle a second `shellQuads=` in through it and have it counted.
        keys.addIfAbsent("flushStages", frame.flushStages());
        keys.addAllIfAbsent(frame.renderCompact());
    }

    /**
     * <b>One frame, as the rows describe it.</b>
     *
     * <p>A record rather than twelve parameters. {@link #of} builds it from the live record, so the
     * production path and the test construct the same value from the same places.
     *
     * @param drawn carried hosts the frame drew
     * @param skippedNoPose hosts skipped for want of a pose
     * @param skippedFar hosts skipped because their carrier was past the draw distance
     * @param skippedStalePose hosts skipped because their carrier's pose had gone stale
     * @param worstPoseAgeTicks the age of the oldest pose drawn from, or {@code -1}
     * @param sight this frame's sight counts
     * @param carriers carriers the client holds
     * @param carriedHosts carried hosts the client holds
     * @param landedHosts landed hosts the client is holding
     * @param fallingBack hosts drawn on the world path because their carrier was let go of
     * @param shellQuads shell quads the frame emitted
     * @param contentQuads component quads the frame emitted
     * @param hostsDrawn hosts the frame emitted something for
     * @param gates the switches the frame was drawn under, as {@code CarrierGeometry.Gates#describe()}
     * @param flushStages which render stage each half reached the frame at
     * @param frameCounters the 13 per-frame counters, as {@code CarrierTrace.Render#frameCounters()}
     * @param renderCompact the client-side picture, as {@code Render#describeWithoutFrameCounters()}
     * @param verdict the sentence a zero has to carry, as {@code Render#geometryVerdict()}
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
                // ONLY WHEN IT IS NOT ZERO. A frame that skipped nothing says nothing, and three `=0`
                // pairs on every line is the kind of always-same text that is pure weight.
                sb.append(" skipped(noPose=").append(skippedNoPose)
                        .append(" stale=").append(skippedStalePose)
                        .append(" far=").append(skippedFar).append(')');
            }
            return sb.toString();
        }

        /**
         * The value of the {@code poseAge} key, always written.
         *
         * <p>The key is never omitted, so a reader can tell "this build does not report ages" from "this
         * frame drew from a fresh pose" - which is the same promise {@code /rc carrier} makes.
         */
        String poseAgePhrase() {
            return CarrierRenderTrace.poseAgePhrase(worstPoseAgeTicks);
        }
    }

    /**
     * One field's <b>name and its {@code =}</b>, with nothing after it.
     *
     * <p>The match deliberately stops at the {@code =}. The first version of this pattern captured the
     * value as {@code ([^=]*)}, which is greedy over everything except {@code =} and therefore
     * <b>swallows the next field's name</b>: on {@code a=1 b=2 c=3} the first match was
     * {@code a=1 b}, so {@code b} was never seen as a key and {@code a}'s value came out as {@code 1 b}.
     * Every other counter on the frame row was lost that way - a defect that read as "the verbose row is
     * missing some counters" rather than as a parsing bug, which is why the value is now taken from the
     * gap <em>between</em> two matches instead of from a capture group.
     *
     * <p>The lookbehind keeps a name from being found in the middle of a token, which matters because the
     * groups being parsed contain hex colours ({@code tint=0x8040FF40} must not yield a field called
     * {@code x8040FF40}) and coordinates.
     */
    private static final Pattern FIELD_NAME = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Za-z][A-Za-z0-9_]*)=");

    /**
     * Splits a formatted {@code key=value …} group into its fields, keeping each value whole.
     *
     * <p>A value is the text between the end of its {@code name=} and the start of the next
     * {@code name=} (or the end of the group), trimmed. Whatever spaces it contains therefore stay in it:
     * the one thing this parser must not do is cut a sentence in half at a space, because the groups it is
     * asked to parse are another class's spelling and this class cannot know where their values end.
     *
     * <p>A value that itself contains {@code name=} (the flush stages are spelled
     * {@code worldStage=AFTER_ENTITIES carriedStage=…}) therefore yields those inner names as fields too.
     * That is deliberate and harmless: the outer key is added first and by hand, so the inner ones are
     * extra names the line did not have - never a second copy of one it did.
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
