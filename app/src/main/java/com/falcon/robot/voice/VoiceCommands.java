package com.falcon.robot.voice;

import com.falcon.robot.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a transcript into a robot action.
 *
 * <p>These sixteen are what the speech recognition puts out, and what the robot answers to
 * wherever the operator is in the app: Forward, Backward, Turn Right, Turn Left, Follow me,
 * Face Recognize, Object Detect, Dance, Stop, Arm Left, Arm Right, Wave, Hello, T-Pose, No and
 * Recording. Every one of them has a button in the Robot Control sidebar, and {@link Action#name}
 * is spelled exactly as the recogniser labels it, so a keyword model's label finds its action.
 *
 * <p>Three of them are the tablet's own work rather than the robot's — Face Recognize, Object
 * Detect and Recording all run here — so their {@code command} is null and the service carries
 * them out itself.
 *
 * <p>The Korean phrases include the ones the tiny-kp model in the assets was trained to say,
 * which is what the Voice page hears when Korean is chosen.
 *
 * <p>English is matched by keyword, so small recognition errors ("move forward please", "go
 * forwards") still work. Chinese, Japanese and Korean are matched as substrings instead: the
 * first two put no spaces between words, and Korean glues endings onto the verb, so "왼쪽으로"
 * and "왼쪽 돌아" share "왼쪽" and little else. Every rule understands all of them, whichever the
 * app is set to — what matters is the language the operator speaks.
 */
public final class VoiceCommands {

    /** A recognized intent. {@code command} is null when the tablet carries it out itself. */
    public static final class Action {
        /** Exactly what the recogniser calls it; stored phrases refer to this name. */
        public final String name;
        /** Display name and one-line description, in the app language. */
        public final int labelRes;
        public final int descriptionRes;
        public final String command;
        /** A switch rather than a one-off order: saying it again undoes what it started. */
        public final boolean toggle;

        Action(String name, int labelRes, int descriptionRes, String command) {
            this(name, labelRes, descriptionRes, command, false);
        }

        Action(String name, int labelRes, int descriptionRes, String command, boolean toggle) {
            this.name = name;
            this.labelRes = labelRes;
            this.descriptionRes = descriptionRes;
            this.command = command;
            this.toggle = toggle;
        }
    }

    /** Follow me: a switch the buttons and the spoken orders share. */
    public static final String FOLLOW = "Follow me";

    /** The three the tablet does itself, by name: what {@code command == null} means for each. */
    public static final String FACE = "Face Recognize";
    public static final String OBJECT = "Object Detect";
    public static final String RECORDING = "Recording";

    private static final String[] NONE = {};

    private static final class Rule {
        final String[][] keywords; // English groups: every group must match one of its words
        final Action action;
        String[] excluded = NONE;
        String[] chinese = NONE;
        String[] japanese = NONE;
        String[] korean = NONE;

        Rule(Action action, String[]... keywords) {
            this.action = action;
            this.keywords = keywords;
        }

        /** Words that rule this one out, in any language: "arm left" is not "turn left". */
        Rule not(String... words) {
            excluded = words;
            return this;
        }

        Rule zh(String... phrases) {
            chinese = phrases;
            return this;
        }

        Rule ja(String... phrases) {
            japanese = phrases;
            return this;
        }

        Rule ko(String... phrases) {
            korean = phrases;
            return this;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        add(new Rule(new Action("Forward", R.string.cmd_forward, R.string.cmd_forward_desc,
                "MOVE FORWARD"), new String[] {"forward", "forwards", "ahead", "straight"})
                .zh("前进", "向前", "往前")
                .ja("前進", "前へ", "進め")
                .ko("전진", "앞으로", "직진"));
        add(new Rule(new Action("Backward", R.string.cmd_backward, R.string.cmd_backward_desc,
                "MOVE BACKWARD"), new String[] {"back", "backward", "backwards", "reverse"})
                .zh("后退", "向后", "往后")
                .ja("後退", "下がれ", "バック")
                .ko("후진", "뒤로", "물러"));
        add(new Rule(new Action("Turn Right", R.string.cmd_right, R.string.cmd_right_desc,
                "TURN RIGHT"), new String[] {"right"})
                .not("arm", "手", "腕", "팔")
                .zh("右转", "向右")
                .ja("右折", "右へ", "右に")
                .ko("오른쪽", "우회전", "우측"));
        add(new Rule(new Action("Turn Left", R.string.cmd_left, R.string.cmd_left_desc,
                "TURN LEFT"), new String[] {"left"})
                .not("arm", "手", "腕", "팔")
                .zh("左转", "向左")
                .ja("左折", "左へ", "左に")
                .ko("왼쪽", "좌회전", "좌측"));
        add(new Rule(new Action(FOLLOW, R.string.cmd_follow, R.string.cmd_follow_desc,
                "FOLLOW", true), new String[] {"follow"})
                .zh("跟我走", "跟随", "跟着我")
                .ja("ついてきて", "追従", "フォロー")
                .ko("따라와", "따라 와", "날따라", "날 따라", "따르시오", "추종"));
        add(new Rule(new Action(FACE, R.string.cmd_face, R.string.cmd_face_desc, null, true),
                new String[] {"face"})
                .zh("人脸识别", "识别人脸")
                .ja("顔認識", "顔の認識")
                .ko("얼굴"));
        add(new Rule(new Action(OBJECT, R.string.cmd_object, R.string.cmd_object_desc, null, true),
                new String[] {"object", "objects", "target", "detect", "detection"})
                .zh("目标检测", "物体识别", "追踪目标")
                .ja("物体検出", "対象追跡")
                .ko("대상", "물체", "객체"));
        add(new Rule(new Action("Dance", R.string.cmd_dance, R.string.cmd_dance_desc,
                "POSE DANCE"), new String[] {"dance", "dancing"})
                .zh("跳舞", "跳个舞")
                .ja("踊って", "ダンス")
                .ko("춤", "댄스"));
        add(new Rule(new Action("Stop", R.string.cmd_stop, R.string.cmd_stop_desc, "STOP"),
                new String[] {"stop", "halt", "freeze"})
                .not("record", "recording") // "stop recording" is the camera, not the robot
                .zh("停止", "停下", "别动")
                .ja("停止", "止まれ", "ストップ")
                .ko("정지", "멈춰", "스톱", "그만"));
        add(new Rule(new Action("Arm Left", R.string.cmd_arm_left, R.string.cmd_arm_left_desc,
                "ARM SELECT LEFT_ARM"), new String[] {"arm"}, new String[] {"left"})
                .zh("左臂", "左手")
                .ja("左腕", "左手")
                .ko("왼팔", "왼쪽 팔", "왼손"));
        add(new Rule(new Action("Arm Right", R.string.cmd_arm_right, R.string.cmd_arm_right_desc,
                "ARM SELECT RIGHT_ARM"), new String[] {"arm"}, new String[] {"right"})
                .zh("右臂", "右手")
                .ja("右腕", "右手")
                .ko("오른팔", "오른쪽 팔", "오른손"));
        add(new Rule(new Action("Wave", R.string.cmd_wave, R.string.cmd_wave_desc, "POSE WAVE"),
                new String[] {"wave", "waving"})
                .zh("挥手", "打招呼")
                .ja("手を振", "挨拶")
                .ko("손 흔들", "손흔들", "인사"));
        add(new Rule(new Action("Hello", R.string.cmd_hello, R.string.cmd_hello_desc, "GREET"),
                new String[] {"hello", "hi", "hey"})
                .zh("你好", "您好")
                .ja("こんにちは", "やあ")
                .ko("안녕", "반갑"));
        add(new Rule(new Action("T-Pose", R.string.cmd_tpose, R.string.cmd_tpose_desc,
                "POSE T_POSE"), new String[] {"t", "tee", "tpose"}, new String[] {"pose", "tpose"})
                .zh("t姿势", "标准姿势")
                .ja("tポーズ", "ティーポーズ")
                .ko("티 자세", "티자세", "t 자세"));
        add(new Rule(new Action("No", R.string.cmd_no, R.string.cmd_no_desc, "CANCEL"),
                new String[] {"no", "nope", "cancel"})
                .zh("取消", "不要")
                .ja("いいえ", "キャンセル", "やめ")
                .ko("아니", "취소", "그만두"));
        add(new Rule(new Action(RECORDING, R.string.cmd_recording, R.string.cmd_recording_desc,
                null, true), new String[] {"recording", "record"})
                .zh("录像", "录制")
                .ja("録画", "レコーディング")
                .ko("록화", "녹화", "촬영"));
    }

    private static void add(Rule rule) {
        RULES.add(rule);
    }

    /** The switches that are on just now, by action name. */
    private static final Set<String> switchedOn = new HashSet<>();

    private VoiceCommands() {
    }

    /** Best matching action, or null when nothing matches. */
    public static Action match(String transcript) {
        if (transcript == null) return null;
        String text = transcript.toLowerCase(Locale.US);
        // a padded, letters-only copy so English keywords match whole words only
        String words = " " + text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        for (Rule rule : RULES) {
            if (excludes(text, words, rule.excluded)) continue;
            if (containsAny(text, rule.chinese) || containsAny(text, rule.japanese)
                    || containsAny(text, rule.korean)) {
                return rule.action;
            }
            boolean all = true;
            for (String[] group : rule.keywords) {
                if (!containsWord(words, group)) {
                    all = false;
                    break;
                }
            }
            if (all) return rule.action;
        }
        return null;
    }

    private static boolean containsAny(String text, String[] phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    /**
     * Whether a word rules the rule out. English is matched whole ("warm" is not "arm"), and the
     * other languages as substrings, the same way their phrases are matched.
     */
    private static boolean excludes(String text, String words, String[] excluded) {
        for (String word : excluded) {
            boolean ascii = word.charAt(0) < 128;
            if (ascii ? words.contains(" " + word + " ") : text.contains(word)) return true;
        }
        return false;
    }

    private static boolean containsWord(String words, String[] group) {
        for (String word : group) {
            if (words.contains(" " + word + " ")) return true;
        }
        return false;
    }

    /**
     * The command to send for an action, or null when the tablet carries it out itself. A switch
     * alternates: "Follow me" starts following, and saying it again stops.
     */
    public static synchronized String commandFor(Action action) {
        if (action == null || action.command == null) return null;
        if (!action.toggle) return action.command;
        return action.command
                + (setSwitchedOn(action.name, !isSwitchedOn(action.name)) ? " START" : " STOP");
    }

    /** Whether a switch is on. The buttons and the spoken orders share this, so they agree. */
    public static synchronized boolean isSwitchedOn(String name) {
        return switchedOn.contains(name);
    }

    /** Sets a switch, and gives back what it was set to. */
    public static synchronized boolean setSwitchedOn(String name, boolean on) {
        if (on) {
            switchedOn.add(name);
        } else {
            switchedOn.remove(name);
        }
        return on;
    }

    /** All built-in actions, in rule order. */
    public static List<Action> actions() {
        List<Action> actions = new ArrayList<>();
        for (Rule rule : RULES) actions.add(rule.action);
        return actions;
    }

    /**
     * Example phrase for an action, used by the command list: what to say in {@code language}
     * ("zh", "ja", anything else falls back to English).
     */
    public static String examplePhrase(Action action, String language) {
        for (Rule rule : RULES) {
            if (rule.action != action) continue;
            if ("zh".equals(language) && rule.chinese.length > 0) return rule.chinese[0];
            if ("ja".equals(language) && rule.japanese.length > 0) return rule.japanese[0];
            if ("ko".equals(language) && rule.korean.length > 0) return rule.korean[0];
            StringBuilder phrase = new StringBuilder();
            for (String[] group : rule.keywords) {
                if (phrase.length() > 0) phrase.append(" ");
                phrase.append(group[0]);
            }
            return phrase.toString();
        }
        return action.name.toLowerCase(Locale.US);
    }

    public static Action actionByName(String name) {
        for (Rule rule : RULES) {
            if (rule.action.name.equals(name)) return rule.action;
        }
        return null;
    }
}
