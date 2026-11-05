package javax.microedition.lcdui;

public class Command {
    public static final int OK = 4, BACK = 2, EXIT = 7, SCREEN = 1, ITEM = 8;
    private final String label;
    private final int type, priority;

    public Command(String label, int type, int priority) {
        this.label = label; this.type = type; this.priority = priority;
    }
    public String getLabel() { return label; }
    public int getCommandType() { return type; }
    public int getPriority() { return priority; }
}
