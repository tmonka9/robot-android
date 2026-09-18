package com.falcon.robot.voice;

import com.falcon.robot.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a transcript into a robot action.
 *
 * <p>English is matched by keyword, so small recognition errors ("move forward please", "go
 * forwards") still work. Chinese and Japanese have no spaces between words, so their phrases are
 * matched as substrings instead. Every rule understands all three languages, whichever the app is
 * set to — what matters is the language the operator speaks, and whisper transcribes all of them.
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

        Action(String name, int labelRes, int descriptionRes, String command) {
            this.name = name;
            this.labelRes = labelRes;
            this.descriptionRes = descriptionRes;
            this.command = command;
        }
    }

    public static final Action ANSWER_TIME =
            new Action("Tell Time", R.string.cmd_time, R.string.cmd_time_desc, null);

    private static final String[] NONE = {};

    private static final class Rule {
        final String[][] keywords; // English groups: every group must match one of its words
        final Action action;
        String[] chinese = NONE;
        String[] japanese = NONE;

        Rule(Action action, String[]... keywords) {
            this.action = action;
            this.keywords = keywords;
        }

        Rule zh(String... phrases) {
            chinese = phrases;
            return this;
        }

        Rule ja(String... phrases) {
            japanese = phrases;
            return this;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        add(new Rule(new Action("Stop", R.string.cmd_stop, R.string.cmd_stop_desc, "STOP"),
                new String[] {"stop", "halt", "freeze"})
                .zh("停止", "停下", "别动")
                .ja("停止", "止まれ", "ストップ"));
        add(new Rule(new Action("Go Home", R.string.cmd_home, R.string.cmd_home_desc, "GO_HOME"),
                new String[] {"home", "dock", "base"})
                .zh("回家", "返回原点", "充电座")
                .ja("ホーム", "帰還", "充電ドック"));
        add(new Rule(new Action("Move Forward", R.string.cmd_forward, R.string.cmd_forward_desc, "MOVE FORWARD"),
                new String[] {"forward", "forwards", "ahead", "straight"})
                .zh("前进", "向前", "往前")
                .ja("前進", "前へ", "進め"));
        add(new Rule(new Action("Move Backward", R.string.cmd_backward, R.string.cmd_backward_desc, "MOVE BACKWARD"),
                new String[] {"back", "backward", "backwards", "reverse"})
                .zh("后退", "向后", "往后")
                .ja("後退", "下がれ", "バック"));
        add(new Rule(new Action("Turn Left", R.string.cmd_left, R.string.cmd_left_desc, "TURN LEFT"),
                new String[] {"left"})
                .zh("左转", "向左")
                .ja("左折", "左へ", "左に"));
        add(new Rule(new Action("Turn Right", R.string.cmd_right, R.string.cmd_right_desc, "TURN RIGHT"),
                new String[] {"right"})
                .zh("右转", "向右")
                .ja("右折", "右へ", "右に"));
        add(new Rule(new Action("Open Door", R.string.cmd_door, R.string.cmd_door_desc, "DOOR OPEN"),
                new String[] {"open"}, new String[] {"door", "gate"})
                .zh("开门", "打开门")
                .ja("ドアを開", "開門", "ドアオープン"));
        add(new Rule(new Action("Start Mapping", R.string.cmd_mapping, R.string.cmd_mapping_desc, "SLAM MAPPING START"),
                new String[] {"start", "begin"}, new String[] {"map", "mapping", "scan"})
                .zh("开始建图", "建图", "扫描地图")
                .ja("マッピング開始", "地図作成", "スキャン開始"));
        add(new Rule(new Action("Stand", R.string.cmd_stand, R.string.cmd_stand_desc, "POSE STAND"),
                new String[] {"stand", "standing"})
                .zh("站起来", "起立", "站立")
                .ja("立って", "起立", "スタンド"));
        add(new Rule(new Action("Sit", R.string.cmd_sit, R.string.cmd_sit_desc, "POSE SIT"),
                new String[] {"sit", "sitting"})
                .zh("坐下", "蹲下")
                .ja("座って", "着席"));
        add(new Rule(new Action("Wave", R.string.cmd_wave, R.string.cmd_wave_desc, "POSE WAVE"),
                new String[] {"wave", "hello", "hi"})
                .zh("挥手", "打招呼")
                .ja("手を振", "挨拶"));
        add(new Rule(new Action("Dance", R.string.cmd_dance, R.string.cmd_dance_desc, "POSE DANCE"),
                new String[] {"dance", "dancing"})
                .zh("跳舞", "跳个舞")
                .ja("踊って", "ダンス"));
        add(new Rule(new Action("Follow Me", R.string.cmd_follow, R.string.cmd_follow_desc, "FOLLOW START"),
                new String[] {"follow"})
                .zh("跟我走", "跟随", "跟着我")
                .ja("ついてきて", "追従", "フォロー"));
        add(new Rule(ANSWER_TIME, new String[] {"time"})
                .zh("几点", "时间")
                .ja("何時", "時刻", "時間"));
    }

    private static void add(Rule rule) {
        RULES.add(rule);
    }

    private VoiceCommands() {
    }

    /** Best matching action, or null when nothing matches. */
    public static Action match(String transcript) {
        if (transcript == null) return null;
        String text = transcript.toLowerCase(Locale.US);
        // a padded, letters-only copy so English keywords match whole words only
        String words = " " + text.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ") + " ";
        for (Rule rule : RULES) {
            if (containsAny(text, rule.chinese) || containsAny(text, rule.japanese)) return rule.action;
            boolean all = true;
            for (String[] group : rule.keywords) {
                boolean found = false;
                for (String word : group) {
                    if (words.contains(" " + word + " ")) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
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

    /** Removes a leading wake word ("hey robot, move forward" → "move forward"). */
    public static String stripWakeWord(String transcript, String wakeWord) {
        String lower = transcript.toLowerCase(Locale.US);
        String wake = wakeWord.toLowerCase(Locale.US);
        int index = lower.indexOf(wake);
        if (index < 0) return null;
        String rest = transcript.substring(index + wake.length());
        return rest.replaceFirst("^[,.!?、。\\s]+", "").trim();
    }

    public static boolean containsWakeWord(String transcript, String wakeWord) {
        return transcript != null && transcript.toLowerCase(Locale.US).contains(wakeWord.toLowerCase(Locale.US));
    }
}
