package com.falcon.robot.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a transcript into a robot action by keyword matching, so small recognition errors
 * ("move forward please", "go forwards") still work.
 */
public final class VoiceCommands {

    /** A recognized intent. {@code command} is null when the robot answers instead of moving. */
    public static final class Action {
        public final String name;
        public final String description;
        public final String command;

        Action(String name, String description, String command) {
            this.name = name;
            this.description = description;
            this.command = command;
        }
    }

    public static final Action ANSWER_TIME =
            new Action("Tell Time", "Robot will tell the current time.", null);

    private static final class Rule {
        final String[][] keywords; // groups: every group must match one of its words
        final Action action;

        Rule(Action action, String[]... keywords) {
            this.action = action;
            this.keywords = keywords;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();

    static {
        RULES.add(new Rule(new Action("Stop", "Robot will stop all movement.", "STOP"),
                new String[] {"stop", "halt", "freeze"}));
        RULES.add(new Rule(new Action("Go Home", "Robot will return to the home position.", "GO_HOME"),
                new String[] {"home", "dock", "base"}));
        RULES.add(new Rule(new Action("Move Forward", "Robot will move forward.", "MOVE FORWARD"),
                new String[] {"forward", "forwards", "ahead", "straight"}));
        RULES.add(new Rule(new Action("Move Backward", "Robot will move backward.", "MOVE BACKWARD"),
                new String[] {"back", "backward", "backwards", "reverse"}));
        RULES.add(new Rule(new Action("Turn Left", "Robot will turn left.", "TURN LEFT"),
                new String[] {"left"}));
        RULES.add(new Rule(new Action("Turn Right", "Robot will turn right.", "TURN RIGHT"),
                new String[] {"right"}));
        RULES.add(new Rule(new Action("Open Door", "Robot will open the door.", "DOOR OPEN"),
                new String[] {"open"}, new String[] {"door", "gate"}));
        RULES.add(new Rule(new Action("Start Mapping", "Robot will start building a map.", "SLAM MAPPING START"),
                new String[] {"start", "begin"}, new String[] {"map", "mapping", "scan"}));
        RULES.add(new Rule(new Action("Stand", "Robot will stand up.", "POSE STAND"),
                new String[] {"stand", "standing"}));
        RULES.add(new Rule(new Action("Sit", "Robot will sit down.", "POSE SIT"),
                new String[] {"sit", "sitting"}));
        RULES.add(new Rule(new Action("Wave", "Robot will wave hello.", "POSE WAVE"),
                new String[] {"wave", "hello", "hi"}));
        RULES.add(new Rule(new Action("Dance", "Robot will dance.", "POSE DANCE"),
                new String[] {"dance", "dancing"}));
        RULES.add(new Rule(new Action("Follow Me", "Robot will follow you.", "FOLLOW START"),
                new String[] {"follow"}));
        RULES.add(new Rule(ANSWER_TIME, new String[] {"time"}));
    }

    private VoiceCommands() {
    }

    /** Best matching action, or null when nothing matches. */
    public static Action match(String transcript) {
        if (transcript == null) return null;
        String text = " " + transcript.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ") + " ";
        for (Rule rule : RULES) {
            boolean all = true;
            for (String[] group : rule.keywords) {
                boolean found = false;
                for (String word : group) {
                    if (text.contains(" " + word + " ")) {
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

    /** All built-in actions, in rule order. */
    public static List<Action> actions() {
        List<Action> actions = new ArrayList<>();
        for (Rule rule : RULES) actions.add(rule.action);
        return actions;
    }

    /** Example phrase for an action, used by the command list. */
    public static String examplePhrase(Action action) {
        for (Rule rule : RULES) {
            if (rule.action == action) {
                StringBuilder phrase = new StringBuilder();
                for (String[] group : rule.keywords) {
                    if (phrase.length() > 0) phrase.append(" ");
                    phrase.append(group[0]);
                }
                return phrase.toString();
            }
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
        return rest.replaceFirst("^[,.!?\\s]+", "").trim();
    }

    public static boolean containsWakeWord(String transcript, String wakeWord) {
        return transcript != null && transcript.toLowerCase(Locale.US).contains(wakeWord.toLowerCase(Locale.US));
    }
}
