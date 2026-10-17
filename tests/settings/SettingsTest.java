package code.HUD;

import code.HUD.Base.GameKeyboard;
import code.Rendering.DirectX7;
import code.utils.*;
import code.utils.canvas.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;
import javax.microedition.lcdui.Graphics;
import javax.microedition.rms.RecordStore;

public final class SettingsTest {
    private static final String STORE="Quantum.M3GPerspective";
    private static final String LEGACY="SettingsTest";
    private static void check(boolean b,String why) {if(!b) throw new AssertionError(why);}
    private static void load(String config) throws Exception {
        Main.settings=new GameIni(config,false);
        Method method=Main.class.getDeclaredMethod("loadRendererSettings");method.setAccessible(true);method.invoke(null);
    }
    private static void put(String store,byte[] value) {RmsUtils.removeStore(store);RmsUtils.saveStore(store,value);}
    private static void record(String store,int value) {
        byte[] data=RmsUtils.openStore(store);check(data!=null && data.length==1 && data[0]==value,"wrong independent record: "+store);
    }
    private static void ui(Main main,MyCanvas menu,boolean language) {
        Main.renderer=DirectX7.SOFTWARE;Main.m3gPerspective=true;Main.persQ=4;
        Setting setting=new Setting(main,menu,null);int count=language?22:21,index=count-1;
        check(setting.getItems().length==count,"Software includes hardware option or changed language row");
        check(setting.getItems()[index].startsWith("Renderer:"),"renderer no longer last Software row");
        setting.setItemIndex(index);setting.onKey6();
        check(Main.renderer==DirectX7.M3G && setting.getItems().length==count+1 && setting.itemIndex()==index && setting.list.left,"M3G insertion lost renderer selection/alignment");
        check(setting.getItems()[index+1].equals("HW perspective: ON") && !setting.list.midSel[index+1],"missing selectable hardware row immediately after renderer");
        setting.setItemIndex(index+1);setting.onKey4();
        check(!Main.m3gPerspective && setting.getItems()[index+1].equals("HW perspective: OFF"),"LEFT didn't disable");
        setting.onKey6();check(Main.m3gPerspective,"RIGHT didn't enable");
        setting.onKey5();check(!Main.m3gPerspective,"FIRE didn't toggle off");
        setting.onLeftSoftKey();check(Main.m3gPerspective,"SELECT didn't toggle on");
        setting.onKey4();setting.onRightSoftKey();record(STORE,0);record("Quantum.Renderer",DirectX7.M3G);
        Main.m3gPerspective=true;loadUnchecked();check(!Main.m3gPerspective && Main.renderer==DirectX7.M3G,"BACK didn't persist option");
        check(Main.mainCanvas.getScreen()==menu && Main.persQ==4,"BACK target / original PERSQ changed");
        setting.setItemIndex(index);setting.onKey4();
        check(Main.renderer==DirectX7.SOFTWARE && setting.getItems().length==count && setting.itemIndex()==index && setting.list.left,"Software removal lost selection or left stale row");
        setting.onKey6();check(!Main.m3gPerspective && setting.getItems()[index+1].equals("HW perspective: OFF"),"renderer switching lost stored hardware preference");
        for(int i=0;i<setting.getItems().length;i++) check(setting.getItems()[i]!=null,"unfilled UI row "+i);
    }
    private static void loadUnchecked() {try {load("RENDERER=Software\n");} catch(Exception e) {throw new RuntimeException(e);}}
    public static void main(String[] args) throws Exception {
        Field name=Main.class.getDeclaredField("savename");name.setAccessible(true);name.set(null,LEGACY);
        Main main=new Main();Main.mainCanvas=new MainCanvas(main);
        String[] keys={"ON","OFF","BACK","AUDIO","MUSIC","FOOTSTEPS","SOUNDS","CONTROLS","MOUSE_SPEED","CHANGE_KEYS","PERFORMANCE","DISPLAY_SIZE","PERSQ","TEXQ","HQ","LQ","LLQ","FOGQ","CORPSES","BLOOD","MIPMAPPING","FRAMESKIP","HUD","LANG","RESIZE_WEAPONS","HIDEHUD","DEBUG","YES","NO","SELECT","CONTINUE","RELOAD","SAVE_GAME","OPTIONS","MENU","EXIT"};
        String text="";for(String key:keys) text+=key+"="+key+"\n";Main.gameText=new IniFile(text,false);
        load("");check(Main.renderer==DirectX7.SOFTWARE && Main.m3gPerspective,"missing stores must preserve old defaults");
        load("RENDERER=M3G\nM3G_PERSPECTIVE=0\n");check(Main.renderer==DirectX7.M3G && !Main.m3gPerspective,"config default OFF ignored");
        for(byte[] bad:new byte[][]{new byte[0],new byte[]{2},new byte[]{-1},new byte[]{0,1}}) {
            put(STORE,bad);load("M3G_PERSPECTIVE=1\n");check(Main.m3gPerspective,"invalid RMS record overrode config");
            load("M3G_PERSPECTIVE=0\n");check(!Main.m3gPerspective,"invalid RMS record forced default ON");
        }
        put(STORE,new byte[]{0});put("Quantum.Renderer",new byte[]{DirectX7.M3G});load("");
        check(!Main.m3gPerspective && Main.renderer==DirectX7.M3G,"independent RMS overrides ignored");
        put(STORE,new byte[]{1});load("M3G_PERSPECTIVE=0\n");check(Main.m3gPerspective,"saved ON didn't override config OFF");
        RmsUtils.removeStore(STORE);load("");check(Main.m3gPerspective,"old Renderer-only install lost ON default");
        RecordStore.fail=true;load("RENDERER=M3G\nM3G_PERSPECTIVE=0\n");check(!Main.m3gPerspective && Main.renderer==DirectX7.M3G,"RMS failure lost config");RecordStore.fail=false;
        System.out.println("PASS default ON / optional config / restart RMS precedence / old Renderer-only record / invalid+missing+unavailable RMS");

        MyCanvas menu=new MyCanvas(){protected void paint(Graphics g){}};
        Main.lang=0;Main.langs=new String[]{"ENGLISH"};ui(main,menu,false);
        Main.langs=new String[]{"ENGLISH","RUSSIAN"};ui(main,menu,true);
        ui(main,new PauseScreen(main,null,null,0),false);
        Main.gameText=new IniFile(text+"M3G_PERSPECTIVE=Hardware PC\n",false);
        Setting translated=new Setting(main,menu,null);check(translated.getItems()[translated.getItems().length-1].startsWith("Hardware PC:"),"language override ignored");
        System.out.println("PASS real settings UI / conditional M3G row / LEFT+RIGHT+FIRE+SELECT / insertion+removal / old PERSQ unchanged / one+two languages / pause+BACK save / translation fallback");

        GameKeyboard.keyCodes=new int[]{17,42};GameKeyboard.hasKeyCodes=new boolean[]{true,false};
        Main.m3gPerspective=true;main.saveSettingToStore();byte[] original=RmsUtils.openStore(LEGACY);
        Main.m3gPerspective=false;main.saveSettingToStore();record(STORE,0);record("Quantum.Renderer",Main.renderer);
        check(Arrays.equals(original,RmsUtils.openStore(LEGACY)),"hardware toggle changed original settings bytes");
        DataInputStream data=new DataInputStream(new ByteArrayInputStream(original));
        for(int v:new int[]{Main.music,Main.sounds,Main.footsteps,Main.getDisplaySize(),Main.availableLevel,Main.persQ,Main.fogQ,Main.pixelsQ}) check(data.readInt()==v,"legacy integer layout changed");
        for(boolean v:new boolean[]{Main.frameskip,Main.corpses,Main.blood,Main.mipMapping,DeveloperMenu.debugMode}) check(data.readBoolean()==v,"legacy boolean layout changed");
        check(data.readInt()==Main.lang && data.readInt()==Main.mouseSpeed && data.readBoolean()==Main.hideHud && data.readBoolean()==Main.resizeWeapons && data.readInt()==Main.lastLevel,"legacy HUD/language layout changed");
        check(data.readInt()==2 && data.readBoolean() && data.readInt()==17 && !data.readBoolean() && data.available()==0,"legacy keyboard record gained extra fields");
        load("M3G_PERSPECTIVE=1\n");check(!Main.m3gPerspective,"OFF didn't survive restart");
        Main.m3gPerspective=true;main.saveSettingToStore();Main.m3gPerspective=false;load("M3G_PERSPECTIVE=0\n");check(Main.m3gPerspective,"ON didn't survive restart");
        RecordStore.fail=true;main.saveSettingToStore();RecordStore.fail=false;
        System.out.println("PASS unchanged original settings/Renderer binary layouts / independent one-byte record / ON+OFF restart / unavailable RMS save nonfatal");
    }
}
