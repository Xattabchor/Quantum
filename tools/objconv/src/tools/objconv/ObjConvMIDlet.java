package tools.objconv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.Item;
import javax.microedition.lcdui.ItemCommandListener;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Ticker;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * ObjConverter - конвертер между бинарным форматом мешей Quantum ("3d",
 * Room.createFrom3d) и Wavefront OBJ, прямо на телефоне (JSR-75 FileConnection).
 *
 * Два режима (выбор в главном меню, потом браузер файлов с отметкой):
 *   3D → OBJ - экспорт моделей движка в текстовый OBJ (для Blender и др.);
 *   OBJ → 3D - импорт OBJ в формат движка (кладите результат в JAR,
 *              путь прописывается в ключе MODEL= уровня).
 *
 * Управление: OK/центр - войти/отметить/запустить; софт-1 (левая) - действие
 * контекста; софт-2/Back - назад; в браузере отметка нескольких файлов и
 * «Конвертировать» конвертирует пакетом, с прогрессом и журналом.
 *
 * UI повторяет проверенный QctConverter (tools/qctconv); кодек - чистый
 * Obj3d (тестируется на JRE: docs/verify/test_objconv.py).
 */
public class ObjConvMIDlet extends MIDlet implements CommandListener, ItemCommandListener, Runnable {

    static final int MODE_3D_TO_OBJ = 0;
    static final int MODE_OBJ_TO_3D = 1;

    private static final String APP_NAME = "Quantum OBJ Converter";
    private static final String VERSION = "1.0";

    private Display display;
    private List menu;
    private List browser;
    private String currentDir;          // null => корневые директории
    private Vector roots;               // Vector<String>
    private final Vector log = new Vector();
    private int mode = MODE_3D_TO_OBJ;

    private Form settingsForm;
    private ChoiceGroup filterGroup;    // список файлов в режиме 3D→OBJ
    private ChoiceGroup skipGroup;      // пропускать существующие
    private boolean filterAllFiles = false;
    private boolean skipExisting = false;

    private Form gaugeForm;
    private Gauge gauge;
    private StringItem noteItem;
    private Thread worker;
    private Vector pendingTargets;
    private TextBox helpBox;

    private final Command cmdExit = new Command("Выход", Command.EXIT, 99);
    private final Command cmdBack = new Command("Назад", Command.BACK, 10);
    private final Command cmdSelect = new Command("OK", Command.OK, 1);
    private final Command cmdRun = new Command("Конвертировать", Command.OK, 1);

    public ObjConvMIDlet() {
    }

    protected void startApp() throws MIDletStateChangeException {
        if(display == null) {
            display = Display.getDisplay(this);
            buildMenu();
        }
        display.setCurrent(menu);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        notifyDestroyed();
    }

    private void buildMenu() {
        menu = new List(APP_NAME, Choice.IMPLICIT);
        menu.append("3D → OBJ: экспорт модели", null);
        menu.append("OBJ → 3D: модель для движка", null);
        menu.append("Самопроверка кодека", null);
        menu.append("Настройки", null);
        menu.append("Журнал (" + log.size() + ")", null);
        menu.append("Справка", null);
        menu.addCommand(cmdExit);
        menu.setCommandListener(this);
        menu.setTicker(new Ticker("OBJ <-> Quantum 3D   |   OK - войти, софт-2 - назад   |   " + VERSION));
    }

    private void showMenu() {
        menu.set(4, "Журнал (" + log.size() + ")", null);
        display.setCurrent(menu);
    }

    // ------------------------------------------------------------- команды

    public void commandAction(Command c, Displayable d) {
        if(c == cmdExit) {
            try {
                destroyApp(true);
            } catch (MIDletStateChangeException e) {
            }
            return;
        }
        if(d == menu) {
            if(c == List.SELECT_COMMAND || c == cmdSelect) {
                int i = menu.getSelectedIndex();
                if(i == 0) {
                    mode = MODE_3D_TO_OBJ;
                    openRoots();
                } else if(i == 1) {
                    mode = MODE_OBJ_TO_3D;
                    openRoots();
                } else if(i == 2) {
                    selfTest();
                } else if(i == 3) {
                    showSettings();
                } else if(i == 4) {
                    showLog();
                } else if(i == 5) {
                    showHelp();
                }
            } else if(c == cmdBack) {
                notifyDestroyed();
            }
            return;
        }
        if(d == browser) {
            if(c == cmdBack) {
                goUp();
            } else if(c == cmdRun) {
                startConversion();
            } else if(c == List.SELECT_COMMAND) {
                browserItemAction();
            }
            return;
        }
        if(d == settingsForm) {
            if(c == cmdBack || c == cmdSelect) {
                applySettings();
                showMenu();
            }
            return;
        }
        if(c == cmdBack || c == cmdSelect) {
            if(d instanceof Alert || d == helpBox || d instanceof List) {
                showMenu();
            }
        }
    }

    public void commandAction(Command c, Item item) {
    }

    // ------------------------------------------------------------- браузер

    private void openRoots() {
        currentDir = null;
        roots = new Vector();
        try {
            String[] props = new String[]{"fileconn.dir.private", "fileconn.dir.memorycard",
                    "fileconn.dir.photos", "fileconn.dir.videos", "fileconn.dir.recordings",
                    "fileconn.dir.roots.names"};
            for(int i = 0; i < props.length; i++) {
                String v = kb(props[i]);
                if(v != null && v.length() > 0) {
                    // в одном свойстве может быть несколько корней через разделители
                    splitRoots(v, roots);
                }
            }
        } catch (Exception e) {
        }
        if(roots.isEmpty()) {
            Enumeration e = null;
            try {
                e = FileSystemRegistry.listRoots();
            } catch (Exception ex) {
            }
            if(e != null) {
                while(e.hasMoreElements()) {
                    String r = (String) e.nextElement();
                    if(r != null && !roots.contains(r)) roots.addElement(r);
                }
            }
        }
        if(roots.isEmpty()) {
            roots.addElement("file:///");
        }
        browser = new List(modeTitle() + " - корень", Choice.MULTIPLE);
        for(int i = 0; i < roots.size(); i++) {
            String r = (String) roots.elementAt(i);
            if(r != null && r.length() > 0) browser.append(r, null);
        }
        browser.addCommand(cmdRun);
        browser.addCommand(cmdBack);
        browser.addCommand(cmdExit);
        browser.setCommandListener(this);
        browser.setSelectCommand(cmdSelect);
        browser.setTicker(new Ticker(modeHint()));
        display.setCurrent(browser);
    }

    private static void splitRoots(String v, Vector out) {
        // свойства Nokia дают пути вида "file:///E:/..." (иногда несколько через ';')
        int pos = 0;
        while(pos < v.length()) {
            int sc = v.indexOf(';', pos);
            String part = sc < 0 ? v.substring(pos) : v.substring(pos, sc);
            pos = sc < 0 ? v.length() : sc + 1;
            part = part.trim();
            if(part.length() > 0 && !out.contains(part)) out.addElement(part);
        }
    }

    private String modeTitle() {
        return mode == MODE_3D_TO_OBJ ? "3D → OBJ" : "OBJ → 3D";
    }

    private String modeHint() {
        return mode == MODE_3D_TO_OBJ
                ? "Отметьте файлы .3d и жмите «Конвертировать»"
                : "Отметьте файлы .obj и жмите «Конвертировать»";
    }

    private boolean wantFile(String name) {
        if(mode == MODE_OBJ_TO_3D) {
            return name.toLowerCase().endsWith(".obj");
        }
        return filterAllFiles || name.toLowerCase().endsWith(".3d");
    }

    private void goUp() {
        if(currentDir == null) {
            showMenu();
            return;
        }
        String parent = parentOf(currentDir);
        if(parent == null || parent.length() == 0 || parent.equals("file:///")) {
            openRoots();
        } else {
            browse(parent);
        }
    }

    private void browse(String dir) {
        currentDir = dir;
        browser = new List(modeTitle() + " - " + shortName(dir), Choice.MULTIPLE);
        FileConnection fc = null;
        Vector dirs = new Vector();
        Vector files = new Vector();
        try {
            fc = (FileConnection) Connector.open(dir, Connector.READ);
            if(!fc.exists()) {
                fc.close();
                fc = null;
                logLine("нет пути: " + dir);
                alert("Ошибка", "Папка недоступна: " + dir, AlertType.ERROR);
                openRoots();
                return;
            }
            Enumeration en = fc.list();
            while(en.hasMoreElements()) {
                String nm = (String) en.nextElement();
                if(nm == null || nm.length() == 0) continue;
                if(nm.endsWith("/")) {
                    if(!nm.equals("./") && !nm.equals("../")) dirs.addElement(nm);
                } else if(wantFile(nm)) {
                    files.addElement(nm);
                }
            }
        } catch (Exception e) {
            logLine("листинг не удался: " + e);
        } finally {
            close(fc);
        }
        browser.append("..", null);
        for(int i = 0; i < dirs.size(); i++) browser.append((String) dirs.elementAt(i), null);
        for(int i = 0; i < files.size(); i++) browser.append((String) files.elementAt(i), null);
        browser.addCommand(cmdRun);
        browser.addCommand(cmdBack);
        browser.addCommand(cmdExit);
        browser.setCommandListener(this);
        browser.setSelectCommand(cmdSelect);
        browser.setTicker(new Ticker(modeHint() + "  |  " + files.size() + " файл(ов)"));
        display.setCurrent(browser);
    }

    private void browserItemAction() {
        int i = browser.getSelectedIndex();
        if(i < 0) return;
        String nm = browser.getString(i);
        if(nm == null) return;
        if(nm.equals("..")) {
            goUp();
            return;
        }
        if(nm.endsWith("/")) {
            String next = currentDir == null ? rootOf(nm) : (currentDir + nm);
            browse(next);
            return;
        }
        // отметка файла: MULTIPLE-список сам ставит галочку; подсказка в тикере
        browser.setTicker(new Ticker("Отмечено: " + countSelected() + "  |  «Конвертировать» - запуск"));
    }

    private int countSelected() {
        boolean[] sel = new boolean[browser.size()];
        browser.getSelectedFlags(sel);
        int n = 0;
        for(int i = 0; i < sel.length; i++) {
            if(sel[i] && !isNavRow(i)) n++;
        }
        return n;
    }

    private boolean isNavRow(int i) {
        String s = browser.getString(i);
        return s == null || s.equals("..") || s.endsWith("/");
    }

    // ------------------------------------------------------------- конверсия

    private void startConversion() {
        if(worker != null) {
            alert("Занято", "Конвертация уже идёт", AlertType.INFO);
            return;
        }
        Vector targets = new Vector();
        String dir = currentDir;
        if(dir == null) {
            alert("Ошибка", "Сначала войдите в папку", AlertType.ERROR);
            return;
        }
        boolean[] sel = new boolean[browser.size()];
        browser.getSelectedFlags(sel);
        for(int i = 0; i < sel.length; i++) {
            if(!sel[i] || isNavRow(i)) continue;
            String nm = browser.getString(i);
            targets.addElement(dir + nm);
        }
        if(targets.isEmpty()) {
            alert("Ничего не отмечено", "Отметьте галочками файлы и снова нажмите «Конвертировать»", AlertType.INFO);
            return;
        }
        pendingTargets = targets;
        gaugeForm = new Form(modeTitle());
        gauge = new Gauge("Готовность", false, targets.size(), 0);
        noteItem = new StringItem(null, "");
        gaugeForm.append(gauge);
        gaugeForm.append(noteItem);
        gaugeForm.addCommand(cmdExit);
        gaugeForm.setCommandListener(this);
        display.setCurrent(gaugeForm);
        worker = new Thread(this, "objconv");
        worker.start();
    }

    public void run() {
        final Vector targets = pendingTargets;
        pendingTargets = null;
        if(targets == null) {
            worker = null;
            return;
        }
        int n = targets.size();
        int ok = 0;
        int fail = 0;
        for(int i = 0; i < n; i++) {
            String path = (String) targets.elementAt(i);
            setProgress(i, "Конвертирую " + (i + 1) + "/" + n + ": " + shortName(path));
            try {
                String res = convertOne(path);
                if(res != null && res.length() > 0) {
                    logLine(res);
                    if(res.startsWith("SKIP")) {
                        // пропуск не считаем ни успехом, ни ошибкой
                    } else {
                        ok++;
                    }
                }
            } catch (Throwable t) {
                String msg = shortName(path) + ": ОШИБКА " + t;
                logLine(msg);
                setProgress(i, msg);
                fail++;
            }
        }
        setProgress(n, "Готово: успешно " + ok + ", ошибок " + fail);
        worker = null;
        final String summary = modeTitle() + ": успешно " + ok + ", ошибок " + fail + " из " + n;
        final boolean allOk = fail == 0;
        logLine(summary);
        display.callSerially(new Runnable() {
            public void run() {
                alert("Итог", summary + "\n\nРезультаты лежат рядом с исходниками.\nПодробности - в Журнале.",
                        allOk ? AlertType.CONFIRMATION : AlertType.WARNING);
                showMenu();
            }
        });
    }

    private void setProgress(final int value, final String note) {
        display.callSerially(new Runnable() {
            public void run() {
                if(gauge != null) gauge.setValue(value);
                if(noteItem != null) noteItem.setText(note);
            }
        });
    }

    /** Конвертирует один файл; возвращает строку для журнала. */
    private String convertOne(String path) throws Exception {
        String name = shortName(path);
        String srcDir = parentOf(path);
        String dstName = mode == MODE_3D_TO_OBJ ? swapExt(name, ".3d", ".obj")
                                                : swapExt(name, ".obj", ".3d");
        String dstPath = srcDir + dstName;
        FileConnection src = null;
        FileConnection dst = null;
        try {
            src = (FileConnection) Connector.open(path, Connector.READ);
            if(!src.exists()) throw new IOException("исходник не найден: " + name);
            byte[] in = readAll(src);

            dst = (FileConnection) Connector.open(dstPath, Connector.READ_WRITE);
            if(dst.exists()) {
                if(skipExisting) {
                    return "SKIP " + name + ": " + dstName + " уже есть";
                }
                dst.delete();
            }
            dst.create();

            int[] stats = new int[3];
            byte[] out;
            if(mode == MODE_3D_TO_OBJ) {
                String obj = Obj3d.toObj(in, stats);
                out = getAsciiBytes(obj);
            } else {
                out = Obj3d.fromObj(newString(in), stats);
            }
            OutputStream os = dst.openDataOutputStream();
            try {
                os.write(out);
                os.flush();
            } finally {
                try {
                    os.close();
                } catch (Exception e) {
                }
            }
            return name + " → " + dstName + "  [" + stats[0] + " меш(ей), "
                    + stats[1] + " верш., " + stats[2] + " граней, " + out.length + " Б]";
        } finally {
            close(src);
            close(dst);
        }
    }

    static String swapExt(String name, String oldExt, String newExt) {
        String lower = name.toLowerCase();
        if(lower.endsWith(oldExt)) {
            return name.substring(0, name.length() - oldExt.length()) + newExt;
        }
        return name + newExt;
    }

    private static byte[] readAll(FileConnection fc) throws IOException {
        InputStream is = fc.openDataInputStream();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            while(true) {
                int r = is.read(buf);
                if(r < 0) break;
                bos.write(buf, 0, r);
                total += r;
                if(total > 8 * 1024 * 1024) throw new IOException("файл больше 8 МБ");
            }
            return bos.toByteArray();
        } finally {
            try {
                is.close();
            } catch (Exception e) {
            }
        }
    }

    private static String newString(byte[] b) {
        try {
            return new String(b, "UTF-8");
        } catch (Exception e) {
            return new String(b);
        }
    }

    private static byte[] getAsciiBytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    // ------------------------------------------------------------- настройки

    private void showSettings() {
        settingsForm = new Form("Настройки");
        filterGroup = new ChoiceGroup("Список файлов 3D → OBJ", Choice.EXCLUSIVE);
        filterGroup.append("только *.3d", null);
        filterGroup.append("все файлы", null);
        filterGroup.setSelectedIndex(filterAllFiles ? 1 : 0, true);
        skipGroup = new ChoiceGroup("Если результат уже есть", Choice.MULTIPLE);
        skipGroup.append("пропускать (SKIP)", null);
        skipGroup.setSelectedIndex(0, skipExisting);
        settingsForm.append(filterGroup);
        settingsForm.append(skipGroup);
        settingsForm.addCommand(cmdSelect);
        settingsForm.addCommand(cmdBack);
        settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }

    private void applySettings() {
        if(filterGroup != null) filterAllFiles = filterGroup.getSelectedIndex() == 1;
        if(skipGroup != null) skipExisting = skipGroup.isSelected(0);
    }

    // ------------------------------------------------------------- журнал

    private void logLine(String s) {
        log.addElement(s);
        while(log.size() > 200) log.removeElementAt(0);
    }

    private void showLog() {
        if(log.isEmpty()) logLine("журнал пуст");
        List l = new List("Журнал (" + log.size() + ")", Choice.IMPLICIT);
        for(int i = log.size() - 1; i >= 0; i--) {
            l.append((String) log.elementAt(i), null);
        }
        l.addCommand(cmdBack);
        l.setCommandListener(this);
        display.setCurrent(l);
    }

    // ------------------------------------------------------------- самопроверка

    private void selfTest() {
        boolean ok = true;
        StringBuffer sb = new StringBuffer();
        try {
            // синтетический 3d: 2 меша, 3V+4V, маркеры материала, UV 0..255
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeInt(2);
            // mesh0: 4 вершины, 1 треугольник (mat 3), 1 квадрат (тот же mat)
            d.writeShort(4);
            d.writeShort(0); d.writeShort(0); d.writeShort(0);
            d.writeShort(100); d.writeShort(0); d.writeShort(0);
            d.writeShort(100); d.writeShort(50); d.writeShort(0);
            d.writeShort(0); d.writeShort(50); d.writeShort(0);
            d.writeShort(1);
            d.writeShort(Obj3d.MAT_MARKER); d.writeShort(3);
            d.writeShort(0); d.writeShort(1); d.writeShort(2);
            d.writeByte(0); d.writeByte(0); d.writeByte(255); d.writeByte(0);
            d.writeByte(255); d.writeByte(255);
            d.writeShort(1);
            d.writeShort(0); d.writeShort(1); d.writeShort(2); d.writeShort(3);
            d.writeByte(128); d.writeByte(64); d.writeByte(32); d.writeByte(16);
            d.writeByte(8); d.writeByte(4); d.writeByte(255); d.writeByte(255);
            // mesh1: 3 вершины, 1 треугольник (mat 0 - стартовое значение, маркер не нужен)
            d.writeShort(3);
            d.writeShort(-10); d.writeShort(-20); d.writeShort(-30);
            d.writeShort(32767); d.writeShort(-32768); d.writeShort(1);
            d.writeShort(7); d.writeShort(8); d.writeShort(9);
            d.writeShort(1);
            // без маркера: mat0 - стартовое значение блока движка
            d.writeShort(0); d.writeShort(1); d.writeShort(2);
            d.writeByte(1); d.writeByte(2); d.writeByte(3); d.writeByte(4);
            d.writeByte(5); d.writeByte(6);
            d.writeShort(0);
            d.flush();
            byte[] data = bos.toByteArray();

            int[] st1 = new int[3];
            String obj = Obj3d.toObj(data, st1);
            int[] st2 = new int[3];
            byte[] back = Obj3d.fromObj(obj, st2);
            if(st1[0] != 2 || st1[1] != 7 || st1[2] != 3
                    || st2[0] != st1[0] || st2[1] != st1[1] || st2[2] != st1[2]) {
                ok = false;
                sb.append("статистика не совпала: ").append(st1[0]).append('/').append(st1[1])
                  .append('/').append(st1[2]).append(" vs ").append(st2[0]).append('/')
                  .append(st2[1]).append('/').append(st2[2]).append('\n');
            }
            if(back.length != data.length) {
                ok = false;
                sb.append("round-trip размер: ").append(back.length)
                  .append(" != ").append(data.length).append('\n');
            } else {
                for(int i = 0; i < back.length; i++) {
                    if(back[i] != data[i]) {
                        ok = false;
                        sb.append("round-trip байт ").append(i).append(": ")
                          .append(back[i]).append(" != ").append(data[i]).append('\n');
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            ok = false;
            sb.append("исключение: ").append(t).append('\n');
        }
        String verdict = ok ? "Самопроверка PASSED" : "Самопроверка FAILED";
        logLine(verdict + (sb.length() > 0 ? ": " + sb : ""));
        alert("Самопроверка", verdict + (sb.length() > 0 ? "\n" + sb : "\n2 меша, 3V+4V, материалы, UV 0..255: round-trip байт-в-байт"),
                ok ? AlertType.CONFIRMATION : AlertType.ERROR);
    }

    // ------------------------------------------------------------- справка

    private void showHelp() {
        if(helpBox == null) {
            helpBox = new TextBox("Справка", helpText(), 4000, TextField.ANY);
            helpBox.addCommand(cmdBack);
            helpBox.setCommandListener(this);
        }
        display.setCurrent(helpBox);
    }

    private static String helpText() {
        return APP_NAME + " " + VERSION + "\n\n"
                + "Конвертер между форматом мешей Quantum (\"3d\") и Wavefront OBJ.\n\n"
                + "3D → OBJ: откройте папку с моделями, отметьте файлы .3d (или все - см. Настройки) и нажмите «Конвертировать». Рядом появятся .obj - их открывает Blender и др.\n\n"
                + "OBJ → 3D: отметьте .obj; результат - файл .3d рядом. Скопируйте его в JAR игры на ПК и пропишите путь в ключе MODEL= уровня.\n\n"
                + "Формат OBJ для движка:\n"
                + "- каждая группа o/g = отдельный меш; несколько мешей в файле = кадры Morphing-анимации;\n"
                + "- v x y z - целые координаты (движок масштабирует их сам), диапазон ±32767;\n"
                + "- vt u v - 0..1 на КАЖДЫЙ угол грани (в движке UV побайтовые);\n"
                + "- f a/ta b/tb c/tc (3 или 4 угла; больше - авторазбиение веером);\n"
                + "- usemtl - материал (берутся первые цифры имени: mat12 → 12);\n"
                + "- vn/mtllib/s игнорируются (нормали движок считает сам).\n\n"
                + "Управление: OK/центр - войти/отметить; софт-1 «Конвертировать» - запуск отмеченного; софт-2 - назад. Журнал хранит последние 200 записей.\n\n"
                + "Проверьте кодек пунктом «Самопроверка кодека» - round-trip синтетической модели байт-в-байт.";
    }

    // ------------------------------------------------------------- хелперы

    static String shortName(String path) {
        if(path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    static String parentOf(String path) {
        if(path == null) return null;
        String p = path;
        if(p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        if(i <= 0) return "file:///";
        return p.substring(0, i + 1);
    }

    static String rootOf(String name) {
        return "file:///" + name;
    }

    private static String kb(String key) {
        try {
            return System.getProperty(key);
        } catch (Exception e) {
            return null;
        }
    }

    private static void close(FileConnection fc) {
        if(fc != null) {
            try {
                fc.close();
            } catch (Exception e) {
            }
        }
    }

    private void alert(String title, String text, AlertType type) {
        Alert a = new Alert(title, text, null, type);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, menu);
    }
}
