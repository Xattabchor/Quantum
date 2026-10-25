package tools.vlaconv;

import java.io.ByteArrayOutputStream;
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
 * VlaConverter - конвертер lightmap.vla между DMODE 13 (цвет) и 9 (серый) прямо на телефоне.
 * Работает как ObjConverter/QctConverter: браузер JSR-75, отметка файлов, фоновый поток,
 * прогресс Gauge, журнал 200 строк. Для разбора vla нужен .3d меш-файл - ищется в той же папке.
 */
public class VlaConvMIDlet extends MIDlet implements CommandListener, ItemCommandListener, Runnable {

    static final int MODE_13_TO_9 = 0;
    static final int MODE_9_TO_13 = 1;

    private static final String APP_NAME = "VLA Converter";
    private static final String VERSION = "1.0";

    private Display display;
    private List menu;
    private List browser;
    private String currentDir;
    private Vector roots;
    private final Vector log = new Vector();
    private int mode = MODE_13_TO_9;

    // настройки
    private Form settingsForm;
    private ChoiceGroup overwriteGroup; // перезаписывать или суффикс _9/_13
    private boolean overwrite = false;

    // прогресс
    private Form gaugeForm;
    private Gauge gauge;
    private StringItem noteItem;
    private Thread worker;
    private Vector pendingTargets;
    private String pendingMeshPath; // опционально выбранный .3d

    private List meshPicker;
    private TextBox helpBox;

    private final Command cmdExit = new Command("Выход", Command.EXIT, 99);
    private final Command cmdBack = new Command("Назад", Command.BACK, 10);
    private final Command cmdSelect = new Command("OK", Command.OK, 1);
    private final Command cmdRun = new Command("Конвертировать", Command.OK, 1);
    private final Command cmdPickMesh = new Command("Выбрать .3d", Command.ITEM, 2);

    public VlaConvMIDlet() {}

    protected void startApp() throws MIDletStateChangeException {
        if (display == null) {
            display = Display.getDisplay(this);
            buildMenu();
        }
        display.setCurrent(menu);
    }
    protected void pauseApp() {}
    protected void destroyApp(boolean u) throws MIDletStateChangeException { notifyDestroyed(); }

    private void buildMenu() {
        menu = new List(APP_NAME, Choice.IMPLICIT);
        menu.append("VLA 13 -> 9 : цвет в серый", null);
        menu.append("VLA 9 -> 13 : серый в цвет", null);
        menu.append("Самопроверка кодека", null);
        menu.append("Настройки", null);
        menu.append("Журнал (" + log.size() + ")", null);
        menu.append("Справка", null);
        menu.addCommand(cmdExit);
        menu.setCommandListener(this);
        menu.setTicker(new Ticker("VLA цвет <-> серый  |  OK - войти  |  " + VERSION));
    }
    private void showMenu() {
        menu.set(4, "Журнал (" + log.size() + ")", null);
        display.setCurrent(menu);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cmdExit) { try { destroyApp(true); } catch (Exception e) {} return; }
        if (d == menu) {
            if (c == List.SELECT_COMMAND || c == cmdSelect) {
                int i = menu.getSelectedIndex();
                if (i == 0) { mode = MODE_13_TO_9; openRoots(); }
                else if (i == 1) { mode = MODE_9_TO_13; openRoots(); }
                else if (i == 2) { selfTest(); }
                else if (i == 3) { showSettings(); }
                else if (i == 4) { showLog(); }
                else if (i == 5) { showHelp(); }
            } else if (c == cmdBack) notifyDestroyed();
            return;
        }
        if (d == browser) {
            if (c == cmdBack) goUp();
            else if (c == cmdRun) startConversion();
            else if (c == cmdPickMesh) pickMesh();
            else if (c == List.SELECT_COMMAND) browserItemAction();
            return;
        }
        if (d == meshPicker) {
            if (c == cmdBack) display.setCurrent(browser);
            else if (c == List.SELECT_COMMAND || c == cmdSelect) {
                int idx = ((List)d).getSelectedIndex();
                if (idx >= 0) {
                    String nm = ((List)d).getString(idx);
                    if (nm.equals("..")) goMeshUp();
                    else if (nm.endsWith("/")) browseMesh(currentDir + nm);
                    else {
                        pendingMeshPath = currentDir + nm;
                        logLine("Выбран меш: " + shortName(pendingMeshPath));
                        display.setCurrent(browser);
                    }
                }
            }
            return;
        }
        if (d == settingsForm) {
            if (c == cmdBack || c == cmdSelect) { applySettings(); showMenu(); }
            return;
        }
        if (c == cmdBack || c == cmdSelect) {
            if (d instanceof Alert || d == helpBox || d instanceof List) showMenu();
        }
    }
    public void commandAction(Command c, Item item) {}

    // --- корни ---
    private void openRoots() {
        currentDir = null;
        pendingMeshPath = null;
        roots = new Vector();
        try {
            String[] props = new String[]{"fileconn.dir.private","fileconn.dir.memorycard","fileconn.dir.photos","fileconn.dir.videos","fileconn.dir.recordings","fileconn.dir.roots.names"};
            for (int i = 0; i < props.length; i++) {
                String v = sysProp(props[i]);
                if (v != null && v.length() > 0) splitRoots(v, roots);
            }
        } catch (Exception e) {}
        if (roots.isEmpty()) {
            Enumeration e = null;
            try { e = FileSystemRegistry.listRoots(); } catch (Exception ex) {}
            if (e != null) while (e.hasMoreElements()) { String r=(String)e.nextElement(); if (r!=null && !roots.contains(r)) roots.addElement(r); }
        }
        if (roots.isEmpty()) roots.addElement("file:///");
        browser = new List(modeTitle()+" - корень", Choice.MULTIPLE);
        for (int i = 0; i < roots.size(); i++) { String r=(String)roots.elementAt(i); if(r!=null&&r.length()>0) browser.append(r,null); }
        browser.addCommand(cmdRun); browser.addCommand(cmdPickMesh); browser.addCommand(cmdBack); browser.addCommand(cmdExit);
        browser.setCommandListener(this); browser.setSelectCommand(cmdSelect);
        browser.setTicker(new Ticker(modeHint()));
        display.setCurrent(browser);
    }
    private static void splitRoots(String v, Vector out) {
        int pos=0; while(pos<v.length()){ int sc=v.indexOf(';',pos); String part=sc<0?v.substring(pos):v.substring(pos,sc); pos=sc<0?v.length():sc+1; part=part.trim(); if(part.length()>0&&!out.contains(part)) out.addElement(part); }
    }
    private String modeTitle(){ return mode==MODE_13_TO_9?"VLA 13->9":"VLA 9->13"; }
    private String modeHint(){ return mode==MODE_13_TO_9?"Отметьте .vla (13 цвет) и «Конвертировать» -> _9.vla":"Отметьте .vla (9 серый) и «Конвертировать» -> _13.vla"; }
    private boolean wantFile(String name){
        String l=name.toLowerCase();
        if (l.endsWith(".vla")) return true;
        // в браузере .3d не показываем как цель, но для выбора меша покажем отдельно
        return false;
    }

    private void goUp(){
        if(currentDir==null){ showMenu(); return; }
        String parent=parentOf(currentDir);
        if(parent==null||parent.length()==0||parent.equals("file:///")) openRoots(); else browse(parent);
    }
    private void browse(String dir){
        currentDir=dir;
        browser=new List(modeTitle()+" - "+shortName(dir), Choice.MULTIPLE);
        FileConnection fc=null; Vector dirs=new Vector(); Vector files=new Vector();
        try{
            fc=(FileConnection)Connector.open(dir, Connector.READ);
            if(!fc.exists()){ close(fc); logLine("нет пути: "+dir); alert("Ошибка","Папка недоступна: "+dir,AlertType.ERROR); openRoots(); return; }
            Enumeration en=fc.list();
            while(en.hasMoreElements()){
                String nm=(String)en.nextElement();
                if(nm==null||nm.length()==0) continue;
                if(nm.endsWith("/")){ if(!nm.equals("./")&&!nm.equals("../")) dirs.addElement(nm); }
                else if(wantFile(nm)) files.addElement(nm);
            }
        }catch(Exception e){ logLine("листинг не удался: "+e); } finally{ close(fc); }
        browser.append("..",null);
        for(int i=0;i<dirs.size();i++) browser.append((String)dirs.elementAt(i),null);
        for(int i=0;i<files.size();i++) browser.append((String)files.elementAt(i),null);
        browser.addCommand(cmdRun); browser.addCommand(cmdPickMesh); browser.addCommand(cmdBack); browser.addCommand(cmdExit);
        browser.setCommandListener(this); browser.setSelectCommand(cmdSelect);
        String meshInfo = pendingMeshPath==null?"меш: авто-поиск .3d в папке":"меш: "+shortName(pendingMeshPath);
        browser.setTicker(new Ticker(modeHint()+" | "+files.size()+" vla | "+meshInfo));
        display.setCurrent(browser);
    }
    private void browserItemAction(){
        int i=browser.getSelectedIndex(); if(i<0) return;
        String nm=browser.getString(i); if(nm==null) return;
        if(nm.equals("..")){ goUp(); return; }
        if(nm.endsWith("/")){ String next=currentDir==null?("file:///"+nm):(currentDir+nm); browse(next); return; }
        browser.setTicker(new Ticker("Отмечено: "+countSelected()+" | «Конвертировать» - запуск | "+(pendingMeshPath==null?"меш авто":"меш "+shortName(pendingMeshPath))));
    }
    private int countSelected(){
        boolean[] sel=new boolean[browser.size()]; browser.getSelectedFlags(sel); int n=0;
        for(int i=0;i<sel.length;i++) if(sel[i]&&!isNavRow(i)) n++;
        return n;
    }
    private boolean isNavRow(int i){ String s=browser.getString(i); return s==null||s.equals("..")||s.endsWith("/"); }

    // --- выбор меша ---
    private void pickMesh(){
        // браузер для .3d в текущей папке/корне
        String dir = currentDir==null? null : currentDir;
        if(dir==null){ // из корней - предложим тот же список корней но фильтр .3d
            openMeshRoots();
        } else {
            browseMesh(dir);
        }
    }
    private void openMeshRoots(){
        currentDir=null;
        Vector r=new Vector();
        try{
            String[] props=new String[]{"fileconn.dir.private","fileconn.dir.memorycard","fileconn.dir.photos"};
            for(int i=0;i<props.length;i++){ String v=sysProp(props[i]); if(v!=null&&v.length()>0) splitRoots(v,r); }
        }catch(Exception e){}
        if(r.isEmpty()){
            Enumeration e=null; try{ e=FileSystemRegistry.listRoots(); }catch(Exception ex){}
            if(e!=null) while(e.hasMoreElements()){ String s=(String)e.nextElement(); if(s!=null&&!r.contains(s)) r.addElement(s); }
        }
        if(r.isEmpty()) r.addElement("file:///");
        meshPicker=new List("Выберите .3d меш", Choice.IMPLICIT);
        for(int i=0;i<r.size();i++) meshPicker.append((String)r.elementAt(i),null);
        meshPicker.addCommand(cmdBack); meshPicker.setCommandListener(this); meshPicker.setTicker(new Ticker("Выберите папку с .3d"));
        display.setCurrent(meshPicker);
    }
    private void browseMesh(String dir){
        currentDir=dir;
        meshPicker=new List("Меш - "+shortName(dir), Choice.IMPLICIT);
        FileConnection fc=null; Vector dirs=new Vector(), files=new Vector();
        try{
            fc=(FileConnection)Connector.open(dir, Connector.READ);
            if(fc.exists()){
                Enumeration en=fc.list();
                while(en.hasMoreElements()){
                    String nm=(String)en.nextElement();
                    if(nm==null) continue;
                    if(nm.endsWith("/")) dirs.addElement(nm);
                    else if(nm.toLowerCase().endsWith(".3d")) files.addElement(nm);
                }
            }
        }catch(Exception e){} finally{ close(fc); }
        meshPicker.append("..",null);
        for(int i=0;i<dirs.size();i++) meshPicker.append((String)dirs.elementAt(i)+"/",null);
        for(int i=0;i<files.size();i++) meshPicker.append((String)files.elementAt(i),null);
        meshPicker.addCommand(cmdSelect); meshPicker.addCommand(cmdBack); meshPicker.setCommandListener(this);
        meshPicker.setTicker(new Ticker(files.size()+" .3d файлов"));
        display.setCurrent(meshPicker);
    }
    private void goMeshUp(){
        if(currentDir==null){ display.setCurrent(browser); return; }
        String p=parentOf(currentDir);
        if(p==null||p.equals("file:///")) openMeshRoots(); else browseMesh(p);
    }

    // --- конверсия ---
    private void startConversion(){
        if(worker!=null){ alert("Занято","Конвертация уже идёт",AlertType.INFO); return; }
        if(browser==null){ alert("Ошибка","Сначала войдите в папку",AlertType.ERROR); return; }
        Vector targets=new Vector(); String dir=currentDir;
        if(dir==null){ alert("Ошибка","Сначала войдите в папку",AlertType.ERROR); return; }
        boolean[] sel=new boolean[browser.size()]; browser.getSelectedFlags(sel);
        for(int i=0;i<sel.length;i++) if(sel[i]&&!isNavRow(i)) targets.addElement(dir+browser.getString(i));
        if(targets.isEmpty()){ alert("Ничего не отмечено","Отметьте .vla галочками",AlertType.INFO); return; }
        pendingTargets=targets;
        gaugeForm=new Form(modeTitle()); gauge=new Gauge("Готовность",false,targets.size(),0);
        noteItem=new StringItem(null,""); gaugeForm.append(gauge); gaugeForm.append(noteItem);
        gaugeForm.addCommand(cmdExit); gaugeForm.setCommandListener(this);
        display.setCurrent(gaugeForm);
        worker=new Thread(this,"vlaconv"); worker.start();
    }
    public void run(){
        final Vector targets=pendingTargets; pendingTargets=null;
        if(targets==null){ worker=null; return; }
        int n=targets.size(); int ok=0, fail=0;
        String meshPathForRun = pendingMeshPath; // защёлкиваем
        for(int i=0;i<n;i++){
            String path=(String)targets.elementAt(i);
            setProgress(i,"Конвертирую "+(i+1)+"/"+n+": "+shortName(path));
            try{
                String res=convertOne(path, meshPathForRun);
                if(res!=null&&res.length()>0){
                    logLine(res);
                    if(!res.startsWith("SKIP") && !res.startsWith("ОШИБКА")) ok++; else if(res.startsWith("ОШИБКА")) fail++;
                }
            }catch(Throwable t){
                String msg=shortName(path)+": ОШИБКА "+t; logLine(msg); setProgress(i,msg); fail++;
            }
        }
        setProgress(n,"Готово: успешно "+ok+", ошибок "+fail);
        worker=null;
        final String summary=modeTitle()+": успешно "+ok+", ошибок "+fail+" из "+n;
        final boolean allOk=fail==0; logLine(summary);
        display.callSerially(new Runnable(){ public void run(){
            alert("Итог",summary+"\n\nРезультаты рядом с исходником.\nПодробности - Журнал.",allOk?AlertType.CONFIRMATION:AlertType.WARNING);
            showMenu();
        }});
    }
    private void setProgress(final int v, final String note){
        display.callSerially(new Runnable(){ public void run(){
            if(gauge!=null) gauge.setValue(v);
            if(noteItem!=null) noteItem.setText(note);
        }});
    }
    private String convertOne(String vlaPath, String forcedMesh) throws Exception {
        String vlaName=shortName(vlaPath);
        String srcDir=parentOf(vlaPath);
        String base=stripExt(vlaName);
        String dstName;
        if(mode==MODE_13_TO_9){
            dstName = overwrite ? vlaName : base+"_9.vla";
        } else {
            dstName = overwrite ? vlaName : base+"_13.vla";
        }
        String dstPath=srcDir+dstName;
        // 1) найти меш
        String meshPath = forcedMesh;
        if(meshPath==null){
            meshPath = findMeshInDir(srcDir);
            if(meshPath==null) throw new Exception("не найден .3d в папке "+srcDir+" (укажите .3d через «Выбрать .3d»)");
        }
        FileConnection meshFc=null, src=null, dst=null;
        try{
            meshFc=(FileConnection)Connector.open(meshPath, Connector.READ);
            if(!meshFc.exists()) throw new Exception("меш не найден: "+shortName(meshPath));
            byte[] meshBytes=readAll(meshFc);
            src=(FileConnection)Connector.open(vlaPath, Connector.READ);
            if(!src.exists()) throw new Exception("исходник не найден: "+vlaName);
            byte[] in=readAll(src);
            dst=(FileConnection)Connector.open(dstPath, Connector.READ_WRITE);
            if(dst.exists() && !overwrite){
                // если файл уже есть и overwrite выкл - пропускаем? нет, перезаписываем с суффиксом уже уникальным
                // но если пользователь выбрал overwrite и файл существует - перезапишем
            }
            if(dst.exists()) dst.delete();
            dst.create();
            int[][] counts = VlaCodec.parseMeshCounts(meshBytes);
            byte[] out;
            int polyTotal=0; for(int i=0;i<counts.length;i++) polyTotal+=counts[i][0]+counts[i][1];
            if(mode==MODE_13_TO_9){
                out=VlaCodec.colorToGray(in, counts);
            } else {
                out=VlaCodec.grayToColor(in, counts);
            }
            OutputStream os=dst.openDataOutputStream();
            try{ os.write(out); os.flush(); } finally{ try{ os.close(); }catch(Exception e){} }
            return vlaName+" -> "+dstName+" ["+counts.length+" мешей, "+polyTotal+" полигонов, "+out.length+" Б]";
        } finally{ close(meshFc); close(src); close(dst); }
    }
    private static String findMeshInDir(String dir){
        FileConnection fc=null;
        try{
            fc=(FileConnection)Connector.open(dir, Connector.READ);
            if(!fc.exists()) return null;
            Enumeration en=fc.list();
            while(en.hasMoreElements()){
                String nm=(String)en.nextElement();
                if(nm!=null && nm.toLowerCase().endsWith(".3d")) return dir+nm;
            }
        }catch(Exception e){} finally{ close(fc); }
        return null;
    }
    private static String stripExt(String name){
        int dot=name.lastIndexOf('.');
        return dot<0?name:name.substring(0,dot);
    }
    private static byte[] readAll(FileConnection fc) throws Exception {
        InputStream is=fc.openDataInputStream();
        try{
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            byte[] buf=new byte[4096]; int total=0;
            while(true){ int r=is.read(buf); if(r<0) break; bos.write(buf,0,r); total+=r; if(total>8*1024*1024) throw new Exception("файл больше 8 МБ"); }
            return bos.toByteArray();
        } finally{ try{ is.close(); }catch(Exception e){} }
    }

    // --- настройки ---
    private void showSettings(){
        settingsForm=new Form("Настройки");
        overwriteGroup=new ChoiceGroup("Выходной файл", Choice.EXCLUSIVE);
        overwriteGroup.append("создавать с суффиксом _9/_13 (не трогать оригинал)",null);
        overwriteGroup.append("перезаписывать оригинал",null);
        overwriteGroup.setSelectedIndex(overwrite?1:0,true);
        settingsForm.append(overwriteGroup);
        settingsForm.addCommand(cmdSelect); settingsForm.addCommand(cmdBack); settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }
    private void applySettings(){ if(overwriteGroup!=null) overwrite=overwriteGroup.getSelectedIndex()==1; }

    private void logLine(String s){ log.addElement(s); while(log.size()>200) log.removeElementAt(0); }
    private void showLog(){
        if(log.isEmpty()) logLine("журнал пуст");
        List l=new List("Журнал ("+log.size()+")", Choice.IMPLICIT);
        for(int i=log.size()-1;i>=0;i--) l.append((String)log.elementAt(i),null);
        l.addCommand(cmdBack); l.setCommandListener(this); display.setCurrent(l);
    }

    private void selfTest(){
        boolean ok=true; StringBuffer sb=new StringBuffer();
        try{
            // синтетика: 2 меши: [1 три+1 квад], [1 три]
            int[][] counts=new int[][]{{1,1},{1,0}};
            byte[] mesh=VlaCodec.buildMeshBytes(counts);
            // цветной vla: 3 полигона (1 три цветной, 1 квад серый уже, 1 три цветной)
            ByteArrayOutputStream bos=new ByteArrayOutputStream();
            java.io.DataOutputStream dos=new java.io.DataOutputStream(bos);
            // полигон 0 (3V цветной): ar 10, br20, cr30, colored true + ag11 ab12 bg21 bb22 cg31 cb32
            dos.writeByte(10-128); dos.writeByte(20-128); dos.writeByte(30-128); dos.writeBoolean(true);
            dos.writeByte(11-128); dos.writeByte(12-128); dos.writeByte(21-128); dos.writeByte(22-128); dos.writeByte(31-128); dos.writeByte(32-128);
            // полигон 1 (4V серый уже): ar40 br50 cr60 dr70 colored false
            dos.writeByte(40-128); dos.writeByte(50-128); dos.writeByte(60-128); dos.writeByte(70-128); dos.writeBoolean(false);
            // полигон 2 (3V цветной 2 меши): ar1 br2 cr3 colored false? нет, сделаем цветным
            dos.writeByte(100-128); dos.writeByte(110-128); dos.writeByte(120-128); dos.writeBoolean(true);
            dos.writeByte(101-128); dos.writeByte(102-128); dos.writeByte(111-128); dos.writeByte(112-128); dos.writeByte(121-128); dos.writeByte(122-128);
            dos.flush(); byte[] color=bos.toByteArray();
            byte[] gray=VlaCodec.colorToGray(color, counts);
            // ожидаем серые: (10+11+12)/3=11, (20+21+22)/3=21, (30+31+32)/3=31, второй 40,50,60,70, третий 101,111,121
            if(gray.length!=10) { ok=false; sb.append("gray len "+gray.length+"!=10 "); }
            else {
                int[] expect=new int[]{11,21,31,40,50,60,70,101,111,121};
                for(int i=0;i<expect.length;i++){ int v=(gray[i]+128)&0xFF; if(v!=expect[i]){ ok=false; sb.append("gray["+i+"]="+v+"!="+expect[i]+" "); break; } }
            }
            byte[] color2=VlaCodec.grayToColor(gray, counts);
            byte[] gray2=VlaCodec.colorToGray(color2, counts);
            if(gray2.length!=gray.length){ ok=false; sb.append("roundtrip len "); }
            else for(int i=0;i<gray.length;i++) if(gray[i]!=gray2[i]){ ok=false; sb.append("roundtrip byte "+i); break; }
        }catch(Throwable t){ ok=false; sb.append("исключение "+t); }
        String verdict=ok?"Самопроверка PASSED":"Самопроверка FAILED";
        logLine(verdict+(sb.length()>0?": "+sb:""));
        alert("Самопроверка",verdict+(sb.length()>0?"\n"+sb:"\n2 меши, 3V+4V, цветной->серый round-trip точный"),ok?AlertType.CONFIRMATION:AlertType.ERROR);
    }

    private void showHelp(){
        if(helpBox==null){
            helpBox=new TextBox("Справка", helpText(), 4000, TextField.ANY);
            helpBox.addCommand(cmdBack); helpBox.setCommandListener(this);
        }
        display.setCurrent(helpBox);
    }
    private static String helpText(){
        return APP_NAME+" "+VERSION+"\n\n"
               +"Конвертер lightmap.vla между DMODE 13 (цвет) и 9 (серый) прямо на телефоне.\n\n"
                +"13->9: усредняет цвет на вершину (R+G+B)/3 -> серый, как делает движок при fogQ==1. Выход *_9.vla в той же папке.\n"
                +"9->13: серый->цвет (r==g==b) сжато (colored==false), рендерится как серый и в 13.\n\n"
                +"Для разбора vla нужен .3d меш той же карты (откуда брались полигоны). Конвертер ищет любой .3d в папке с .vla, или укажите .3d вручную «Выбрать .3d».\n\n"
                +"Управление: OK - войти/отметить, софт-1 «Конвертировать» - запуск отмеченных, «Выбрать .3d» - привязать меш, софт-2 - назад. Журнал 200 записей.\n\n"
                +"Проверьте кодек пунктом «Самопроверка».";
    }

    // --- хелперы ---
    static String shortName(String path){ if(path==null) return ""; int i=path.lastIndexOf('/'); return i>=0?path.substring(i+1):path; }
    static String parentOf(String path){ if(path==null) return null; String p=path; if(p.endsWith("/")) p=p.substring(0,p.length()-1); int i=p.lastIndexOf('/'); if(i<=0) return "file:///"; return p.substring(0,i+1); }
    private static String sysProp(String k){ try{ return System.getProperty(k); }catch(Exception e){ return null; } }
    private static void close(FileConnection fc){ if(fc!=null) try{ fc.close(); }catch(Exception e){} }
    private void alert(String t,String m,AlertType tp){ Alert a=new Alert(t,m,null,tp); a.setTimeout(Alert.FOREVER); display.setCurrent(a,menu); }
}
