package tools.qctconv;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
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
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.midlet.MIDlet;

import code.utils.QctEncoder;

/**
 * Конвертер PNG -&gt; QCT (блок-палитра 2x2, формат, который движок Quantum
 * читает нативно: RawImage.qctToRawImage). Работает прямо на аппарате или в
 * эмуляторе: читает PNG с карты памяти через FileConnection (JSR-75) и кладёт
 * рядом .qct - после чего достаточно положить эти .qct в JAR рядом с .png, и
 * движок подхватит их сам (ключ QCT=1 по умолчанию).
 *
 * Управление:
 *   Soft1/Выбор - войти в папку или отметить PNG галочкой;
 *   Soft2       - конвертировать отмеченные;
 *   * / #       - отметить всё / снять всё;
 *   c           - конвертировать отмеченные;
 *   Меню        - отметить/снять всё, конвертировать всю папку (рекурсивно
 *                 по настройке), в корень, обновить, настройки, справка.
 */
public class QctConvMIDlet extends MIDlet implements CommandListener, Runnable {

    private Display display;

    private List menu;
    private List browser;
    private List log;
    private Form settings;
    private Form progress;
    private Gauge gauge;
    private TextBox help;

    private ChoiceGroup cgColors;
    private ChoiceGroup cgQuality;
    private ChoiceGroup cgFlags;

    private final Command cmdOpen = new Command("Открыть/отметить",
            Command.ITEM, 1);
    private final Command cmdConvert = new Command("Конвертировать",
            Command.ITEM, 2);
    private final Command cmdMenu = new Command("Меню", Command.SCREEN, 3);
    private final Command cmdBack = new Command("Назад", Command.BACK, 4);
    private final Command cmdExit = new Command("Выход", Command.EXIT, 9);

    /** url текущей папки и параллельные списки элементов браузера. */
    private String dirUrl;
    private final Vector entryUrls = new Vector();
    private final Vector entryDirs = new Vector();

    private final Vector logLines = new Vector();

    private volatile boolean running;
    private String[] jobUrls;
    private int doneCount, skipCount, failCount;
    private long inBytes, outBytes;

    public QctConvMIDlet() {
        display = Display.getDisplay(this);

        menu = new List("QCT Converter", Choice.IMPLICIT,
                new String[]{"Карта памяти: обзор и конвертация",
                        "Самопроверка (синтетическая текстура)",
                        "Настройки", "Журнал", "Справка"}, null);
        menu.addCommand(cmdOpen);
        menu.addCommand(cmdExit);
        menu.setSelectCommand(cmdOpen);
        menu.setCommandListener(this);

        settings = new Form("Настройки конвертера");
        cgColors = new ChoiceGroup("Размер палитры", Choice.EXCLUSIVE,
                new String[]{"2 цвета", "4 цвета", "8 цветов", "16 цветов",
                        "32 цвета", "64 цвета"}, null);
        cgColors.setSelectedIndex(1, true);
        cgQuality = new ChoiceGroup("Качество подбора", Choice.EXCLUSIVE,
                new String[]{"быстро", "точно (перебор пар)"}, null);
        cgQuality.setSelectedIndex(0, true);
        cgFlags = new ChoiceGroup(null, Choice.MULTIPLE,
                new String[]{"Пропускать существующие .qct",
                        "«Всю папку» - рекурсивно"}, null);
        cgFlags.setSelectedIndex(0, true);
        settings.append(cgColors);
        settings.append(cgQuality);
        settings.append(cgFlags);
        settings.addCommand(cmdBack);
        settings.setCommandListener(this);

        progress = new Form("Конвертация");
        gauge = new Gauge("Обработка", false, 1, 0);
        progress.append(gauge);

        log = new List("Журнал", Choice.IMPLICIT);
        log.addCommand(cmdBack);
        log.setCommandListener(this);

        help = new TextBox("Справка",
                "QCT - текстуры Quantum: блоки 2x2, 2 бита на пиксель, общая "
                + "палитра (0.75 байта/пиксель).\n"
                + "Конвертер кладёт .qct рядом с .png на карте памяти.\n"
                + "Скопируйте .qct в JAR рядом с .png - движок подхватит их "
                + "сам (ключ QCT=1 по умолчанию, QCT=0 - только png).\n"
                + "Ограничения: ширина и высота обязаны быть чётными "
                + "(блоки 2x2); палитра 2..64 цвета; альфа: прозрачные "
                + "пиксели и полутона сохраняются.\n"
                + "Управление: Soft1 - войти/отметить, Soft2 или c - "
                + "конвертировать отмеченные, * - отметить все, # - снять, "
                + " Меню - конвертировать всю папку.", 900,
                0);
        help.addCommand(cmdBack);
        help.setCommandListener(this);
    }

    protected void startApp() {
        display.setCurrent(menu);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
        running = false;
    }

    // ------------------------------------------------------------------
    public void commandAction(Command c, Displayable d) {
        if(c == cmdExit) {
            running = false;
            notifyDestroyed();
            return;
        }
        if(d == menu) {
            switch (menu.getSelectedIndex()) {
                case 0: openRoots(); break;
                case 1: selfTest(); break;
                case 2: display.setCurrent(settings); break;
                case 3: showLog(); break;
                case 4: display.setCurrent(help); break;
            }
            return;
        }
        if(c == cmdBack) {
            display.setCurrent(menu);
            return;
        }
        if(d == browser) {
            if(c == cmdOpen) {
                int i = browser.getSelectedIndex();
                if(i < 0) return;
                if(i == 0 && dirUrl != null && isUpEntry()) {
                    browse(parentOf(dirUrl));
                    return;
                }
                int idx = isUpEntry() ? i - 1 : i;
                Boolean dir = (Boolean) entryDirs.elementAt(idx);
                if(dir.booleanValue()) {
                    browse((String) entryUrls.elementAt(idx));
                } else {
                    browser.setSelectedIndex(i,
                            !browser.isSelected(i));
                }
                return;
            }
            if(c == cmdConvert) {
                startConversion(markedUrls());
                return;
            }
            if(c == cmdMenu) {
                menuActions();
                return;
            }
        }
    }

    private boolean upEntry;

    private boolean isUpEntry() {
        return upEntry;
    }

    private void menuActions() {
        // простое циклическое меню действий браузера
        List act = new List("Действия", Choice.IMPLICIT,
                new String[]{"Отметить все PNG", "Снять все отметки",
                        "Конвертировать всю папку", "В корень", "Обновить"},
                null);
        act.addCommand(cmdBack);
        act.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if(c == cmdBack) {
                    display.setCurrent(browser);
                    return;
                }
                List l = (List) d;
                switch (l.getSelectedIndex()) {
                    case 0: markAll(true); break;
                    case 1: markAll(false); break;
                    case 2: startConversion(dirPngs(dirUrl,
                            recursive())); break;
                    case 3: openRoots(); return;
                    case 4: browse(dirUrl); return;
                }
                display.setCurrent(browser);
            }
        });
        display.setCurrent(act);
    }

    private boolean skipExisting() {
        return cgFlags.isSelected(0);
    }

    private boolean recursive() {
        return cgFlags.isSelected(1);
    }

    private void markAll(boolean on) {
        for(int i = 0; i < browser.size(); i++) {
            if(isUpEntry() && i == 0) continue;
            int idx = isUpEntry() ? i - 1 : i;
            if(!((Boolean) entryDirs.elementAt(idx)).booleanValue()) {
                browser.setSelectedIndex(i, on);
            }
        }
    }

    // ------------------------------------------------------------------
    private void openRoots() {
        String[] props = new String[]{
                "fileconn.dir.memorycard", "fileconn.dir.private",
                "fileconn.dir.photos", "fileconn.dir.music",
                "fileconn.dir.videos"};
        Vector roots = new Vector();
        for(int i = 0; i < props.length; i++) {
            String r = getProperty(props[i]);
            if(r != null && r.length() > 0 && !contains(roots, r)) {
                roots.addElement(r);
            }
        }
        if(roots.isEmpty()) {
            alert("Нет FileConnection",
                    "Аппарат не сообщает каталоги JSR-75 "
                    + "(fileconn.dir.*). Конвертация с карты памяти "
                    + "недоступна; самопроверка работает.");
            return;
        }
        List rl = new List("Корни", Choice.IMPLICIT);
        for(int i = 0; i < roots.size(); i++) {
            rl.append((String) roots.elementAt(i), null);
        }
        rl.addCommand(cmdOpen);
        rl.addCommand(cmdBack);
        rl.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if(c == cmdBack) {
                    display.setCurrent(menu);
                    return;
                }
                browse(((List) d).getString(
                        ((List) d).getSelectedIndex()));
            }
        });
        display.setCurrent(rl);
    }

    private void browse(String url) {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url);
            if(!fc.isDirectory()) {
                alert("Не папка", url);
                return;
            }
            entryUrls.removeAllElements();
            entryDirs.removeAllElements();
            browser = new List(shortName(url), Choice.MULTIPLE);
            upEntry = !url.equals(rootOf(url));
            if(upEntry) browser.append("..", null);
            java.util.Enumeration en = fc.list();
            Vector dirs = new Vector(), files = new Vector();
            while(en.hasMoreElements()) {
                String name = (String) en.nextElement();
                FileConnection sub = null;
                boolean isDir;
                try {
                    sub = (FileConnection) Connector.open(url + name);
                    isDir = sub.isDirectory();
                } finally {
                    if(sub != null) sub.close();
                }
                if(isDir) dirs.addElement(name);
                else if(name.toLowerCase().endsWith(".png")) {
                    files.addElement(name);
                }
            }
            for(int i = 0; i < dirs.size(); i++) {
                browser.append((String) dirs.elementAt(i) + "/", null);
                entryUrls.addElement(url + dirs.elementAt(i) + "/");
                entryDirs.addElement(Boolean.TRUE);
            }
            for(int i = 0; i < files.size(); i++) {
                browser.append((String) files.elementAt(i), null);
                entryUrls.addElement(url + files.elementAt(i));
                entryDirs.addElement(Boolean.FALSE);
            }
            browser.addCommand(cmdOpen);
            browser.addCommand(cmdConvert);
            browser.addCommand(cmdMenu);
            browser.setSelectCommand(cmdOpen);
            browser.setCommandListener(this);
            dirUrl = url;
            display.setCurrent(browser);
        } catch (Throwable t) {
            alert("Ошибка обзора", t.toString());
        } finally {
            close(fc);
        }
    }

    private String[] markedUrls() {
        Vector v = new Vector();
        for(int i = 0; i < browser.size(); i++) {
            if(!browser.isSelected(i)) continue;
            int idx = isUpEntry() ? i - 1 : i;
            if(idx < 0) continue;
            if(!((Boolean) entryDirs.elementAt(idx)).booleanValue()) {
                v.addElement(entryUrls.elementAt(idx));
            }
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private String[] dirPngs(String url, boolean recursive) {
        Vector v = new Vector();
        collectPngs(url, recursive, v);
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    private void collectPngs(String url, boolean recursive, Vector out) {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(url);
            if(!fc.isDirectory()) return;
            java.util.Enumeration en = fc.list();
            while(en.hasMoreElements()) {
                String name = (String) en.nextElement();
                FileConnection sub = null;
                try {
                    sub = (FileConnection) Connector.open(url + name);
                    if(sub.isDirectory()) {
                        if(recursive) {
                            collectPngs(url + name + "/", true, out);
                        }
                    } else if(name.toLowerCase().endsWith(".png")) {
                        out.addElement(url + name);
                    }
                } finally {
                    close(sub);
                }
            }
        } catch (Throwable t) {
            // пропускаем недоступные каталоги
        } finally {
            close(fc);
        }
    }

    // ------------------------------------------------------------------
    private void startConversion(String[] urls) {
        if(running) {
            alert("Занято", "Конвертация уже идёт.");
            return;
        }
        if(urls == null || urls.length == 0) {
            alert("Нечего конвертировать",
                    "Отметьте PNG галочками (Soft1 на файле) или используйте "
                    + "«Конвертировать всю папку».");
            return;
        }
        jobUrls = urls;
        running = true;
        doneCount = skipCount = failCount = 0;
        inBytes = outBytes = 0;
        gauge.setMaxValue(urls.length);
        gauge.setValue(0);
        display.setCurrent(progress);
        Thread t = new Thread(this);
        t.start();
    }

    public void run() {
        for(int i = 0; i < jobUrls.length && running; i++) {
            final int n = i;
            convertOne(jobUrls[i]);
            display.callSerially(new Runnable() {
                public void run() {
                    gauge.setValue(n + 1);
                }
            });
        }
        running = false;
        final String summary = "Готово: " + doneCount + " сконвертировано, "
                + skipCount + " пропущено, " + failCount + " ошибок\n"
                + kb(inBytes) + " -> " + kb(outBytes);
        logLines.addElement(summary);
        display.callSerially(new Runnable() {
            public void run() {
                alert("Конвертация", summary);
                display.setCurrent(menu);
            }
        });
    }

    private void convertOne(String url) {
        FileConnection src = null, dst = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            src = (FileConnection) Connector.open(url);
            if(!src.exists()) {
                failCount++;
                logLines.addElement(shortName(url) + ": нет файла");
                return;
            }
            String qctUrl = url.substring(0, url.length() - 4) + ".qct";
            dst = (FileConnection) Connector.open(qctUrl);
            if(dst.exists() && skipExisting()) {
                skipCount++;
                logLines.addElement(shortName(url) + ": .qct уже есть");
                return;
            }
            long size = src.fileSize();
            in = src.openInputStream();
            javax.microedition.lcdui.Image img =
                    javax.microedition.lcdui.Image.createImage(in);
            int w = img.getWidth(), h = img.getHeight();
            if((w & 1) != 0 || (h & 1) != 0 || w < 2 || h < 2) {
                failCount++;
                logLines.addElement(shortName(url) + ": размер " + w + "x" + h
                        + " нечётный - QCT требует чётных");
                return;
            }
            int[] rgb = new int[w * h];
            img.getRGB(rgb, 0, w, 0, 0, w, h);
            int cc = 2 << cgColors.getSelectedIndex();
            byte[] qct = QctEncoder.encode(rgb, w, h, cc,
                    cgQuality.getSelectedIndex());
            if(qct == null) {
                failCount++;
                logLines.addElement(shortName(url) + ": энкодер отказал");
                return;
            }
            if(dst.exists()) dst.delete();
            dst.create();
            out = dst.openOutputStream();
            out.write(qct);
            out.close();
            out = null;
            doneCount++;
            inBytes += size;
            outBytes += qct.length;
            long pct = size == 0 ? 0 : qct.length * 100 / size;
            logLines.addElement(shortName(url) + ": " + kb(size) + " -> "
                    + kb(qct.length) + " (" + pct + "% от png)");
        } catch (Throwable t) {
            failCount++;
            logLines.addElement(shortName(url) + ": " + t.toString());
        } finally {
            close(in);
            close(out);
            close(src);
            close(dst);
        }
    }

    // ------------------------------------------------------------------
    private void selfTest() {
        try {
            int w = 48, h = 48;
            int[] rgb = new int[w * h];
            for(int y = 0; y < h; y++) {
                for(int x = 0; x < w; x++) {
                    int dx = x - w / 2, dy = y - h / 2;
                    boolean inside = dx * dx + dy * dy < (w * w) / 6;
                    int c = (((x / 8) & 1) == 0 ? 0xff204060 : 0xffc0a080);
                    rgb[x + y * w] = inside ? c : 0x00000000;
                }
            }
            byte[] qct = QctEncoder.encode(rgb, w, h, 4, 1);
            int[] back = QctEncoder.decode(qct);
            int bad = 0;
            for(int i = 0; i < rgb.length; i++) {
                if(back[i] != rgb[i]) bad++;
            }
            alert("Самопроверка", "48x48, 2 цвета + альфа\nPNG-байт: "
                    + (w * h * 4) + " -> QCT: " + qct.length
                    + "\nрасхождений после распаковки: " + bad
                    + (bad == 0 ? "\nФормат и энкодер исправны." : ""));
        } catch (Throwable t) {
            alert("Самопроверка", "Ошибка: " + t.toString());
        }
    }

    private void showLog() {
        log.deleteAll();
        for(int i = logLines.size() - 1; i >= 0; i--) {
            log.append((String) logLines.elementAt(i), null);
        }
        if(log.size() == 0) log.append("(пусто)", null);
        display.setCurrent(log);
    }

    // ------------------------------------------------------------------
    private void alert(String title, String text) {
        Alert a = new Alert(title, text, null, AlertType.INFO);
        a.setTimeout(4000);
        display.setCurrent(a);
    }

    private static String kb(long b) {
        return b < 10240 ? (b + " Б") : ((b + 512) / 1024 + " КБ");
    }

    private static boolean contains(Vector v, String s) {
        for(int i = 0; i < v.size(); i++) {
            if(v.elementAt(i).equals(s)) return true;
        }
        return false;
    }

    private static String getProperty(String key) {
        try {
            return System.getProperty(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shortName(String url) {
        int i = url.lastIndexOf('/');
        return i >= 0 && i < url.length() - 1 ? url.substring(i + 1) : url;
    }

    private static String parentOf(String url) {
        int end = url.endsWith("/") ? url.length() - 1 : url.length();
        int i = url.lastIndexOf('/', end - 1);
        return i < 0 ? url : url.substring(0, i + 1);
    }

    private static String rootOf(String url) {
        int i = url.indexOf("//");
        if(i < 0) return url;
        int j = url.indexOf('/', i + 2);
        return j < 0 ? url : url.substring(0, j + 1);
    }

    private static void close(Object o) {
        if(o == null) return;
        try {
            if(o instanceof FileConnection) ((FileConnection) o).close();
            else if(o instanceof InputStream) ((InputStream) o).close();
            else if(o instanceof OutputStream) ((OutputStream) o).close();
        } catch (Throwable t) {
        }
    }
}
