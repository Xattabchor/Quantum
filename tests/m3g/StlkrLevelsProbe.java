package code.Rendering;

import code.Gameplay.LevelLoader;
import code.Gameplay.Map.*;
import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import code.utils.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.PolygonMode;

/** All 13 Stlkr worlds, actual lightmaps/portals/skies, fixed weather.
 * No objects, AI, physics, vignette, roof/lightning simulation or GPU timing.
 * Room-center poses are coverage probes, not asserted walkable positions.
 */
public final class StlkrLevelsProbe {
    private static void check(boolean ok,String why){M3GTest.check(ok,why);}
    private static void configure(GameIni level) {
        DirectX7.standartDrawmode=(byte)level.getInt("DMODE",0);
        DirectX7.setDrDist(level.getInt("DIST",Integer.MAX_VALUE));
        DirectX7.setFogDist(level.getInt("FOGD",1));
        DirectX7.fogc=rgb(level.get("FOGCR")+","+level.get("FOGCG")+","+level.get("FOGCB"),0);
    }
    private static int rgb(String value,int fallback) {
        if(value==null || value.indexOf("null")>=0)return fallback;
        int[] v=GameIni.cutOnInts(value,',',';');
        return v.length==1?v[0]*0x010101:v[0]<<16|v[1]<<8|v[2];
    }
    private static Skybox sky(GameIni level) {
        byte saved=DirectX7.standartDrawmode;DirectX7.standartDrawmode=0;
        Skybox sky=null;
        if(level.get("SKYBOX_2D")!=null) {
            String[] p=level.get("SKYBOX_2D").split(",");
            sky=new Skybox(Asset.getTexture(p[0]),level.getFloat("SKYBOX_REPEAT_X",2),level.getFloat("SKYBOX_REPEAT_Y",1),
                Integer.parseInt(p[2]),Float.parseFloat(p[1]),Float.parseFloat(p[3]),
                rgb(level.get("SKYBOX_COLOR"),0),rgb(level.get("SKYBOX_GROUND_COLOR"),0));
        } else if(level.get("SKYBOX_COLOR")!=null)sky=new Skybox(rgb(level.get("SKYBOX_COLOR"),0));
        if(sky!=null) {
            sky.skyboxAlways=level.getInt("SKYBOX_ALWAYS",0)==1;
            if(level.get("SKYBOX_LIGHTING")!=null)sky.skyLighting=Asset.getTexture(level.get("SKYBOX_LIGHTING"));
        }
        DirectX7.standartDrawmode=saved;return sky;
    }
    private static ScreenLines weather(DirectX7 g,GameIni level) {
        if(level.get("WEATHER")==null)return null;
        check(level.get("WEATHER").equals("RAIN"),"unsupported weather fixture");
        WeatherGenerator w=new WeatherGenerator(g.width,g.height,level.getInt("WEATHER_PARTICLES_COUNT",170),
            0xaabbdd,0x666666,0xffffff,0xcaccdf,false,null,4,level.getInt("WEATHER_PARTICLES_SIZE",22),120,160,2,10);
        w.generate=1;w.setRenderer(DirectX7.M3G);
        for(int i=0;i<w.info.length/2;i++) {
            w.info[i*2]=1;w.info[i*2+1]=10+i*8;
            w.coords[i*2]=(20+i*19)%g.width;w.coords[i*2+1]=(25+i*21)%g.height;
        }
        return w.prepareM3G(g);
    }
    private static void sample(int number,GameIni level,House house,Skybox sky,String label,int[] feet,int room,int yaw,int pitch) throws Exception {
        configure(level);DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        Matrix x=new Matrix(),y=new Matrix(),camera=new Matrix();x.setRotX(pitch);y.setRotY(yaw);camera.mul(y,x);
        camera.setPosition(feet[0],feet[1]+1503,feet[2]);g.setCamera(camera);
        if(sky!=null){sky.rotateX=pitch;sky.rotateY=yaw;}
        java.lang.reflect.Field queue=DirectX7.class.getDeclaredField("buffer");queue.setAccessible(true);
        for(int frame=0;frame<4;frame++) {
            Graphics3D.history.clear();g.beginFrameStats();Level13Probe.projected=0;Level13Probe.counting=true;
            if(sky!=null){sky.draw=true;sky.addViewport(0,0,g.width,g.height);}
            house.render(null,g,House.getParts(house,room,camera),feet[0],feet[2]);
            int queued=((RenderObjectBuffer)queue.get(g)).getSize();
            g.renderGameFrameImage(null,false,false,house,room,weather(g,level));Level13Probe.counting=false;
            // An empty no-sky view need not initialize a native backend.
            check(g.isM3GActive() || (queued==0 && g.getRendererError()==null),"level"+number+" native failure: "+g.getRendererError());
            int triangles=0,fogDraws=0,unfogged=0;
            for(Graphics3D.Draw draw:Graphics3D.history)if(draw.cull==PolygonMode.CULL_BACK) {
                triangles+=draw.indices.length/3;if(draw.fog)fogDraws++;else unfogged++;
                check(!draw.lit,"unexpected dynamic world lighting");
                int mode=level.getInt("DMODE",0);
                if(mode==9)check(!draw.fog,"static9 must not acquire Fog");
                if(mode==3 || mode==6)check(draw.fog && draw.fogMode==javax.microedition.m3g.Fog.LINEAR,"lost native LINEAR world Fog");
            }
            if(frame==3) {
                check(g.getM3GTextureUploads()==0,"warm texture upload");
                System.out.println("STLKR level="+number+" sample="+label+" room="+room+" yaw="+yaw+" pitch="+pitch
                    +" D="+g.getM3GDrawCalls()+" triangles="+triangles+" fogDraws="+fogDraws+" noFogDraws="+unfogged
                    +" B="+g.getM3GCpuBarriers()+" T="+g.getM3GTextureUploads()+" projected="+Level13Probe.projected);
            }
        }
        g.destroy();Graphics3D.history.clear();
    }
    public static void main(String[] args) throws Exception {
        Main.settings=new GameIni(Level13Probe.text("/setting.txt"),true);
        Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        Main.floorOffsetSZ=0;Main.isSounds=false;
        Level13Probe.seedImages(args[0]);
        Method cut=LevelLoader.class.getDeclaredMethod("cutLevelFile",String.class);cut.setAccessible(true);
        int samples=0;
        for(int number=1;number<=13;number++) {
            GameIni level=new GameIni(((String[])cut.invoke(null,Level13Probe.text("/level"+number+".txt")))[0],false);
            configure(level);MultyTexture textures=new MultyTexture(level.get("WORLD_TEXTURE"),true);
            Mesh[] meshes=Level13Probe.decode(level.get("WORLD_MODEL"),level.getFloat("WORLD_SCALE",1),textures);
            Room.chunkSize=level.getInt("CHUNK_COLLISION_SIZE",5000);Room.chunkSizeRender=level.getInt("CHUNK_SIZE",0);
            if(level.get("LIGHTMAP")!=null) {
                java.io.InputStream light=StlkrLevelsProbe.class.getResourceAsStream(level.get("LIGHTMAP"));
                if(light==null) {
                    check(number==2,"unexpected missing lightmap");
                    System.out.println("STLKR_WARNING level=2 original archive lacks promzona.vla; retain original default vertex brightness");
                } else light.close();
            }
            House house=HouseCreator.create(meshes,false,level.get("LIGHTMAP"),level.getInt("OPTIMIZE_LEVEL_MODEL",1)==1);
            Skybox sky=sky(level);house.setSkybox(sky);
            List<int[]> points=new ArrayList<int[]>();List<Integer> rooms=new ArrayList<Integer>();List<String> labels=new ArrayList<String>();
            int[] start=GameIni.createPos(level.get("START"),',');int startRoom=house.calcPart(-1,start[0],start[1],start[2],true);
            if(startRoom<0)startRoom=house.calcPart(-1,start[0],start[1],start[2],false);
            check(startRoom>=0,"start outside level"+number);
            points.add(start);rooms.add(startRoom);labels.add("start");
            int vertices=0,polygons=0;
            for(Room room:house.getRooms()) {
                Mesh mesh=room.getMesh();vertices+=mesh.getVertices().length;polygons+=mesh.getPolygons().length;
                points.add(new int[]{(mesh.minX()+mesh.maxX())/2,mesh.minY(),(mesh.minZ()+mesh.maxZ())/2});
                rooms.add(room.getId());labels.add("room"+room.getId());
            }
            System.out.println("STLKR_WORLD level="+number+" rooms="+house.getRooms().length+" vertices="+vertices+" polygons="+polygons
                +" dmode="+level.getInt("DMODE",0)+" dist="+level.getInt("DIST",Integer.MAX_VALUE)+" fogd="+level.getInt("FOGD",1));
            for(int i=0;i<points.size();i++)for(int pitch:new int[]{0,45})for(int yaw=0;yaw<360;yaw+=45) {
                sample(number,level,house,sky,labels.get(i),points.get(i),rooms.get(i),yaw,pitch);samples++;
            }
            house.destroy();
        }
        System.out.println("PASS all 13 Stlkr worlds / "+samples+" warm poses / native skies + fixed weather / static9 no Fog / no AI or FPS measurement");
    }
}
