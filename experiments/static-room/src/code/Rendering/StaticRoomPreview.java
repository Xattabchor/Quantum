package code.Rendering;

import code.Gameplay.Map.Room;
import code.Rendering.Meshes.LightedPolygon3V;
import code.Rendering.Meshes.LightedPolygon4V;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.IOException;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.GameIni;
import code.utils.Main;
import code.utils.StringTools;
import java.util.Vector;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.MIDlet;

/** Separate no-save room viewer, never a replacement for GameScreen. */
public final class StaticRoomPreview extends MIDlet {
    private Viewer viewer;
    protected void startApp(){if(viewer==null)viewer=new Viewer(this);Display.getDisplay(this).setCurrent(viewer);viewer.start();}
    protected void pauseApp(){if(viewer!=null)viewer.stop();}
    protected void destroyApp(boolean unconditional){if(viewer!=null)viewer.close();}

    static int loadLightmap(Mesh[] meshes,InputStream input)throws IOException {
        if(input==null)throw new IOException("lightmap missing");
        DataInputStream in=new DataInputStream(input);int values=0;
        try {
            for(int m=0;m<meshes.length;m++) {
                RenderObject[] faces=meshes[m].getPolygons();
                for(int f=0;f<faces.length;f++) {
                    RenderObject face=faces[f];
                    if(face instanceof LightedPolygon3V){LightedPolygon3V p=(LightedPolygon3V)face;p.la=in.readByte()+128;p.lb=in.readByte()+128;p.lc=in.readByte()+128;values+=3;}
                    else if(face instanceof LightedPolygon4V){LightedPolygon4V p=(LightedPolygon4V)face;p.la=in.readByte()+128;p.lb=in.readByte()+128;p.lc=in.readByte()+128;p.ld=in.readByte()+128;values+=4;}
                    else throw new IOException("preview requires mode9 lighted geometry");
                }
            }
            if(in.read()!=-1)throw new IOException("trailing lightmap bytes");
            return values;
        } finally {in.close();}
    }

    private static final class Viewer extends Canvas implements Runnable {
        private final StaticRoomPreview app;
        private volatile boolean running,closed;
        private Thread worker;
        private StaticRoomMesh nativeRoom;
        private Mesh room;
        private MultyTexture materials;
        private DirectX7 legacy;
        private final Matrix pose=new Matrix();
        private Image surface;
        private volatile boolean cached=true,ready;
        private volatile int turn,move,strafe;
        private int x,y,z,yaw,width,height,far;
        private String error;
        private int fps,frames,draws;
        private long since,lastFrame;
        private int turnRemainder;

        Viewer(StaticRoomPreview app){this.app=app;setFullScreenMode(true);}
        synchronized void start(){running=true;if(worker==null){worker=new Thread(this);worker.start();}}
        void stop(){running=false;}
        void close(){closed=true;stop();}
        public void run(){
            try {
                if(!ready)load();
                since=System.currentTimeMillis();
                while(!closed){if(running){repaint();serviceRepaints();}Thread.sleep(running?1:100);}
            }catch(Throwable ex){error=ex.toString();repaint();}
            finally{if(nativeRoom!=null)nativeRoom.dispose();if(legacy!=null)legacy.destroy();synchronized(this){worker=null;} }
        }
        private void load() throws IOException {
            Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);
            Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
            String text=StringTools.getStringFromResource("/level1.txt");
            String begin="[WTEX_BEGIN]",end="[WTEX_END]";int b=text.indexOf(begin),e=text.indexOf(end);
            if(b<0 || e<b)throw new IllegalArgumentException("WTEX block missing");
            String[] lines=GameIni.cutOnStrings(text.substring(b+begin.length(),e),'\n','\n');Vector valid=new Vector();
            for(int i=0;i<lines.length;i++)if(lines[i].trim().length()>0)valid.addElement(lines[i].trim());
            String[] textures=new String[valid.size()];valid.copyInto(textures);
            GameIni config=new GameIni(text.substring(0,b)+text.substring(e+end.length()),true);
            DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);far=config.getInt("DIST",68500);DirectX7.drDist=far;
            materials=new MultyTexture(textures);
            float scale=config.getFloat("WORLD_SCALE",1);
            Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,materials);
            if(meshes==null || meshes.length==0 || meshes[0]==null)throw new IllegalArgumentException("world load failed");
            String lightmap=config.get("LIGHTMAP");if(lightmap==null)throw new IllegalArgumentException("baked LIGHTMAP required");
            loadLightmap(meshes,StaticRoomPreview.class.getResourceAsStream(lightmap));room=meshes[0];
            int[] start=GameIni.createPos(config.get("START"),',');x=start[0];y=start[1]+1503;z=start[2];
            width=getWidth();height=getHeight();legacy=new DirectX7(width,height);legacy.requireM3G();
            surface=Image.createImage(width,height);
            nativeRoom=new StaticRoomMesh(room,Main.fogQ,true);ready=true;
        }
        protected void paint(Graphics g) {
            if(error!=null){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Preview failed (no fallback)",0,0,20);g.drawString(error,0,20,20);return;}
            if(!ready){g.setColor(0);g.fillRect(0,0,getWidth(),getHeight());g.setColor(0xffffff);g.drawString("Loading static room...",0,0,20);return;}
            try {
                if(width!=getWidth() || height!=getHeight()) {
                    width=getWidth();height=getHeight();legacy.resize(width,height);surface=Image.createImage(width,height);
                }
                long time=System.currentTimeMillis();int elapsed=lastFrame==0?0:(int)Math.min(50,time-lastFrame);lastFrame=time;
                turnRemainder+=turn*elapsed*90;int rotation=turnRemainder/1000;turnRemainder-=rotation*1000;
                yaw=(yaw+rotation+360)%360;
                pose.setRotY(yaw);
                x+=(int)((pose.m02*(long)move+pose.m00*(long)strafe)*elapsed*10/Matrix.FP);
                z+=(int)((pose.m22*(long)move+pose.m20*(long)strafe)*elapsed*10/Matrix.FP);
                pose.setPosition(x,y,z);materials.updateAnimation();
                if(cached) {
                    nativeRoom.render(surface.getGraphics(),pose,width,height,legacy.distX,legacy.distY,far);
                    g.drawImage(surface,0,0,20);draws=nativeRoom.draws;
                } else {
                    legacy.setCamera(pose);legacy.clearDisplay(0x203040);legacy.beginFrameStats();
                    legacy.transformAndProjectVertices(room,legacy.getInvCamera());legacy.addMesh(room,0,0,width,height);
                    legacy.renderGameFrameImage(null,false,false,null,0,null);legacy.flush(g,0,0);draws=legacy.getM3GDrawCalls();
                }
                frames++;long now=System.currentTimeMillis();if(now-since>=1000){fps=(int)(frames*1000L/(now-since));frames=0;since=now;}
                g.setColor(0);g.fillRect(0,0,width,44);g.setColor(0xffffff);
                g.drawString((cached?"A cached world":"B projected painter")+" FPS "+fps+" D "+draws,0,0,20);
                g.drawString("0 A/B; 4/6 turn; 2/8 move; 7/9 strafe",0,15,20);
                g.drawString("ROOM TEST ONLY: no NPC/sky/rain/save",0,30,20);
            }catch(Throwable ex){error=ex.toString();running=false;repaint();}
        }
        protected void keyPressed(int key) {
            if(key==KEY_NUM0){cached=!cached;frames=0;fps=0;since=System.currentTimeMillis();return;}
            if(key==KEY_POUND){close();app.notifyDestroyed();return;}
            if(key==KEY_NUM4)turn=-1;else if(key==KEY_NUM6)turn=1;
            else if(key==KEY_NUM2)move=-1;else if(key==KEY_NUM8)move=1;
            else if(key==KEY_NUM7)strafe=-1;else if(key==KEY_NUM9)strafe=1;
        }
        protected void keyReleased(int key) {
            if(key==KEY_NUM4 || key==KEY_NUM6)turn=0;
            if(key==KEY_NUM2 || key==KEY_NUM8)move=0;
            if(key==KEY_NUM7 || key==KEY_NUM9)strafe=0;
        }
    }
}
