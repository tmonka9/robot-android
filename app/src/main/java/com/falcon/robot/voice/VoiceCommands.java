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
 * <p>These are the orders the robot answers to, wherever the operator is in the app: Go Home,
 * Forward, Backward, Stop, Turn Left, Turn Right, Wave, Dance, Arm Left, Arm Right, Hello,
 * T-Pose, No and Recording. The pages show the same set as buttons.
 *
 * <p>The Korean phrases include the ones the tiny-kp model in the assets was trained to say,
 * which is what the Voice page hears when Korean is chosen.
 *
 * <p>English is matched by keyword, so small recognition errors ("move forward please", "go
 * forwards") still work. Chinese, Japanese and Korean are matched as substrings instead: the
 * first two put no spaces between words, and Korean glues endings onto the verb, so "왼쪽으로"
 * and "왼쪽 돌아" share "왼쪽" and little else. Every rule understands all of them, whichever the
 * app is set to — what matters is the language the operator speaks. The Voice page recognises
 * English; the other phrases are here for whenever a model for those languages is put in the
 * assets.
 */
public final class VoiceCommands {

    /** A recognized intent. {@code command} is null when the robot answers instead of moving. */
    public static final class Action {
        /** Stable English key: what stored phrases refer to, never shown to the operator. */
        public final String name;
        /** Display name and one-line description, in the app language. */
        public final int labelRes;
        public final int descriptionRes;
        public final String command;
        /** A switch rather than a one-off order: saying it again stops what it started. */
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
        add(new Rule(new Action("Go Home", R.string.cmd_home, R.string.cmd_home_desc, "GO_HOME"),
                new String[] {"home", "dock", "base"})
                .zh("回家", "返回原点", "充电座")
                .ja("ホーム", "帰還", "充電ドック")
                .ko("집으로", "자기위치", "첫위치", "본래 위치", "복귀", "도킹", "충전"));
        add(new Rule(new Action("Move Forward", R.string.cmd_forward, R.string.cmd_forward_desc, "MOVE FORWARD"),
                new String[] {"forward", "forwards", "ahead", "straight"})
                .zh("前进", "向前", "往前")
                .ja("前進", "前へ", "進め")
                .ko("전진", "앞으로", "직진"));
        add(new Rule(new Action("Move Backward", R.string.cmd_backward, R.string.cmd_backward_desc, "MOVE BACKWARD"),
                new String[] {"back", "backward", "backwards", "reverse"})
                .zh("后退", "向后", "往后")
                .ja("後退", "下がれ", "バック")
                .ko("후진", "뒤로", "물러"));
        add(new Rule(new Action("Stop", R.string.cmd_stop, R.string.cmd_stop_desc, "STOP"),
                new String[] {"stop", "halt", "freeze"})
                .zh("停止", "停下", "别动")
                .ja("停止", "止まれ", "ストップ")
                .ko("정지", "멈춰", "스톱", "그만"));
        add(new Rule(new Action("Turn Left", R.string.cmd_left, R.string.cmd_left_desc, "TURN LEFT"),
                new String[] {"left"})
                .not("arm", "手", "腕", "팔")
                .zh("左转", "向左")
                .ja("左折", "左へ", "左に")
                .ko("왼쪽", "좌회전", "좌측"));
        add(new Rule(new Action("Turn Right", R.string.cmd_right, R.string.cmd_right_desc, "TURN RIGHT"),
                new String[] {"right"})
                .not("arm", "手", "腕", "팔")
                .zh("右转", "向右")
                .ja("右折", "右へ", "右に")
                .ko("오른쪽", "우회전", "우측"));
        add(new Rule(new Action("Wave", R.string.cmd_wave, R.string.cmd_wave_desc, "POSE WAVE"),
                new String[] {"wave", "waving"})
                .zh("挥手", "打招呼")
                .ja("手を振", "挨拶")
                .ko("손 흔들", "손흔들", "인사"));
        add(new Rule(new Action("Dance", R.string.cmd_dance, R.string.cmd_dance_desc, "POSE DANCE"),
                new String[] {"dance", "dancing"})
                .zh("跳舞", "跳个舞")
                .ja("踊って", "ダンス")
                .ko("춤", "댄스"));
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
        add(new Rule(new Action("Hello", R.string.cmd_hello, R.string.cmd_hello_desc, "GREET"),
                new String[] {"hello", "hi", "hey"})
                .zh("你好", "您好")
                .ja("こんにちは", "やあ")
                .ko("안녕", "반갑"));
        add(new Rule(new Action("T-Pose", R.string.cmd_tpose, R.string.cmd_tpose_desc, "POSE T_POSE"),
                new String[] {"t", "tee", "tpose"}, new String[] {"pose", "tpose"})
                .zh("t姿势", "标准姿势")
                .ja("tポーズ", "ティーポーズ")
                .ko("티 자세", "티자세", "t 자세"));
        add(new Rule(new Action("No", R.string.cmd_no, R.string.cmd_no_desc, "CANCEL"),
                new String[] {"no", "nope", "cancel"})
                .zh("取消", "不要")
                .ja("いいえ", "キャンセル", "やめ")
                .ko("아니", "취소", "그만두"));
        add(new Rule(new Action("Recording", R.string.cmd_recording, R.string.cmd_recording_desc,
                "CAMERA VIDEO", true), new String[] {"recording", "record"})
                .zh("录像", "录制")
                .ja("録画", "レコーディング")
                .ko("록화", "녹화", "촬영"));
    }

    private static void add(Rule rule) {
        RULES.add(rule);
    }

    /** The toggles that are on just now, by action name. */
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
     * The command to send for an action, or null when there is nothing to send. Recording is a
     * switch: saying it starts the recording and saying it again stops it, so the command that
     * goes out alternates between START and STOP.
     */
    public static synchronized String commandFor(Action action) {
        if (action == null || action.command == null) return null;
        if (!action.toggle) return action.command;
        boolean on = !switchedOn.contains(action.name);
        if (on) {
            switchedOn.add(action.name);
        } else {
            switchedOn.remove(action.name);
        }
        return action.command + (on ? " START" : " STOP");
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
