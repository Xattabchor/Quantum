package code.Rendering;

import code.AI.NPC;
import code.Gameplay.LevelLoader;
import code.Gameplay.Map.*;
import code.Gameplay.Objects.*;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.utils.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Graphics3D;
import javax.microedition.m3g.Texture2D;

/** Stlkr level13 snapshot diagnostic on current M3G. Not AI/gameplay/GPU timing.
 * Real decoders, House/portals, MeshObject/SpriteObject and NPC idle render.
 * Uploaded archive classes are excluded; PNG fixture decoding uses ImageIO.
 */
public final class Level13Probe {
    public static boolean counting;
    public static int projected;
    private static boolean active;
    private static final boolean FOG_OFF=Boolean.getBoolean("level13.fogOff");
    private static GameIni levelConfig;
    private static void configureLevel() {
        DirectX7.standartDrawmode=(byte)levelConfig.getInt("DMODE",0);
        DirectX7.setDrDist(levelConfig.getInt("DIST",Integer.MAX_VALUE));DirectX7.setFogDist(levelConfig.getInt("FOGD",1));
        DirectX7.fogc=levelConfig.getInt("FOGCR",0)<<16|levelConfig.getInt("FOGCG",0)<<8|levelConfig.getInt("FOGCB",0);
    }
    private static final Map<RawImage,String> names=new IdentityHashMap<RawImage,String>();
    private static final Map<Texture2D,String> nativeNames=new IdentityHashMap<Texture2D,String>();
    private static final Map<Texture2D,String> nativeSources=new IdentityHashMap<Texture2D,String>();
    private static final List<Sprite> sprites=new ArrayList<Sprite>();
    private static final List<RoomObject> objects=new ArrayList<RoomObject>();
    private static void check(boolean b,String why){M3GTest.check(b,why);}
    // Called only by a temporary test copy of createTexture, never production.
    public static void upload(Texture2D texture,RawImage raw,int kind) {
        if(active) {
            String name=names.get(raw);check(name!=null,"unattributed texture");
            nativeSources.put(texture,name+":"+kind);
            nativeNames.put(texture,kind==M3GSceneRenderer.PANORAMA_TEXTURE?"sky":
                kind==M3GSceneRenderer.SPRITE_CUTOUT || kind==M3GSceneRenderer.SPRITE_ALPHA
                    || kind==M3GSceneRenderer.SPRITE_ADD || kind>=M3GSceneRenderer.SPRITE_MUL?"sprite":
                name.equals("/gamedata/maps/kordon/kordon.png")?"world":"mesh");
        }
    }
    private static InputStream resource(String n) {
        InputStream in=Level13Probe.class.getResourceAsStream(n);
        if(in==null)throw new IllegalArgumentException("Missing level13 resource: "+n);return in;
    }
    private static String text(String n) throws Exception {
        try(InputStream in=resource(n)) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int k;
            while((k=in.read(bytes))!=-1)b.write(bytes,0,k);return new String(b.toByteArray(),"windows-1251").replace("\r","");
        }
    }
    private static Mesh[] decode(String n,float scale,MultyTexture t) throws Exception {
        Method m=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,
            Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);m.setAccessible(true);
        try(DataInputStream in=new DataInputStream(resource(n))) {
            Mesh[] meshes=new Mesh[in.readInt()];
            for(int i=0;i<meshes.length;i++)meshes[i]=(Mesh)m.invoke(null,n,in,scale,scale,scale,t);
            check(in.available()==0,"unconsumed model data "+n);return meshes;
        }
    }
    private static void seedImages(String jar) throws Exception {
        Field f=Asset.class.getDeclaredField("table");f.setAccessible(true);Hashtable cache=(Hashtable)f.get(null);
        try(ZipFile zip=new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                String name=entries.nextElement().getName();if(!name.endsWith(".png"))continue;
                BufferedImage image;try(InputStream in=resource("/"+name)){image=ImageIO.read(in);}
                check(image!=null,"PNG decode "+name);int w=image.getWidth(),h=image.getHeight();
                RawImage raw=new RawImage(image.getRGB(0,0,w,h,null,0,w),w,h);
                names.put(raw,"/"+name);cache.put("RIMG_/"+name,raw);
            }
        }
        // Fixture preseed avoids MIDP image loading on desktop. It is not a
        // phone heap measurement or a request to load all PNGs in the game.
        System.out.println("L13 fixture PNGs="+names.size());
    }
    private static int[] pos(String s){return GameIni.createPos(s,',');}
    private static void add(House house,RoomObject o,GameIni obj,GameIni level) {
        LevelLoader.loadRM(obj,o,level,obj.get("PRESET"));house.addObject(o);
        check(o.getPart()!=-1,"render object outside House "+o.getPosX()+","+o.getPosZ());objects.add(o);
    }
    private static void loadObjects(House house,GameIni level,String file) throws Exception {
        int meshCount=0,spriteCount=0,npcCount=0,spawnerGroups=0;
        String remaining=file.substring(file.indexOf('['));
        while(remaining.startsWith("[")) {
            int end=remaining.indexOf(']');String type=remaining.substring(1,end);
            remaining=remaining.substring(end+1);int next=remaining.indexOf('[');
            String block=next<0?remaining:remaining.substring(0,next);remaining=next<0?"":remaining.substring(next);
            GameIni obj=new GameIni(block,false);if(!type.equals("MESH") && !type.equals("SPRITE") && !type.equals("NPC"))continue;
            if(type.equals("NPC") && obj.get("SPAWN")!=null){spawnerGroups++;continue;}
            for(String xyz:StringTools.cutOnStrings(obj.get("POS"),';')) {
                int[] p=pos(xyz);
                if(type.equals("NPC")) {
                    NPC npc=LevelLoader.loadNPC(obj,obj.get("PRESET"),p);
                    check(npc.meshImage==null,"probe expects sprite NPC presets");
                    add(house,npc,obj,level);npcCount++;
                    for(Sprite s:new Sprite[]{npc.stayFront,npc.staySide,npc.stayBack})if(s!=null)sprites.add(s);
                } else if(type.equals("MESH")) {
                    MultyTexture t=new MultyTexture(obj.get("TEX"),false);
                    MeshObject m=new MeshObject(decode(obj.get("MODEL"),obj.getFloat("SCALE",1),t),t,p[0],p[1],p[2],false);
                    m.getCharacter().getTransform().rotY(obj.getInt("ROT",0));m.addsz=obj.getInt("ADDSZ",m.addsz);
                    add(house,m,obj,level);meshCount++;
                } else {
                    Texture[] tex;String[] paths=StringTools.cutOnStrings(obj.get("TEX"),',');tex=new Texture[paths.length];
                    for(int i=0;i<tex.length;i++)tex[i]=Asset.getTextureNM(paths[i].trim());
                    SpriteObject s=new SpriteObject();s.spr.setTextures(tex);s.spr.setScale(obj.getInt("SCALE",s.spr.scale));
                    s.spr.animation_speed=obj.getFloat("ANIMATION_SPEED",s.spr.animation_speed);
                    s.spr.setMode(obj.getDef("MODE","NULL"));s.spr.setFog(obj.getDef("FOG","NULL"));
                    s.spr.color=obj.getInt("COLOR",s.spr.color);s.addsz=obj.getInt("ADDSZ",s.addsz);s.setPos(p[0],p[1],p[2]);
                    check(obj.get("OFFSET")==null && obj.get("CUTOFF")==null && obj.get("FLICKER")==null && obj.get("COLOR_ANIM")==null,"unsupported sprite fixture override");
                    add(house,s,obj,level);sprites.add(s.spr);spriteCount++;
                }
            }
        }
        System.out.println("L13 objects mesh="+meshCount+" sprite="+spriteCount+" idleNPC="+npcCount+" inactiveSpawnerGroups="+spawnerGroups+" total="+objects.size());
        check(meshCount==5 && spriteCount==23 && npcCount==5 && spawnerGroups==4,"level13 object inventory changed");
    }
    private static void resetReasons() {
        WorldBottleneckProbe.active=true;
        WorldBottleneckProbe.textureSplits=WorldBottleneckProbe.stateSplits=WorldBottleneckProbe.scaleSplits=WorldBottleneckProbe.capacitySplits=0;
        WorldBottleneckProbe.clipAttempts=WorldBottleneckProbe.clipRecovered=WorldBottleneckProbe.clipTriangles=WorldBottleneckProbe.clipDiscards=0;
        WorldBottleneckProbe.rejects.clear();WorldBottleneckProbe.packRejects.clear();
        WorldBottleneckProbe.fogShared=WorldBottleneckProbe.fogLegacy=0;projected=0;
    }
    private static void sample(House house,Skybox sky,String label,int[] feet,int yaw,int pitch,String part) throws Exception {
        // DirectX7.destroy resets shared level globals. Restore them for EVERY
        // fresh sample, otherwise mode3 silently falls back at fDist=1.
        configureLevel();DirectX7 g=new DirectX7(240,320);g.setRenderer(DirectX7.M3G);
        Matrix rx=new Matrix(),ry=new Matrix(),cam=new Matrix();rx.setRotX(pitch);ry.setRotY(yaw);cam.mul(ry,rx);
        cam.setPosition(feet[0],feet[1]+1503,feet[2]);g.setCamera(cam);sky.rotateX=pitch;sky.rotateY=yaw;
        int room=house.calcPart(-1,feet[0],feet[1],feet[2],true);
        if(room==-1)room=house.calcPart(-1,feet[0],feet[1],feet[2],false);
        check(room!=-1,"camera feet outside map "+label);
        Field q=DirectX7.class.getDeclaredField("buffer");q.setAccessible(true);
        for(RoomObject o:objects)o.visible=part.equals("objects");
        nativeNames.clear();nativeSources.clear();active=true;
        for(int frame=0;frame<4;frame++) {
            Graphics3D.history.clear();g.beginFrameStats();resetReasons();counting=true;
            int reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls;
            // Fixed idle snapshot: restart sprite animations just before the
            // real render, so the fire/idle animation begins at frame zero.
            for(Sprite s:sprites){s.animationBegin=System.currentTimeMillis();s.anim_index=0;}
            sky.draw=true;sky.addViewport(0,0,g.width,g.height);
            house.render(null,g,House.getParts(house,room,cam),feet[0],feet[2]);
            int queued=((RenderObjectBuffer)q.get(g)).getSize();
            g.renderGameFrameImage(null,false,false,house,room,null);counting=false;
            check(g.isM3GActive(),"level13 native backend failed: "+g.getRendererError());
            check(DirectX7.standartDrawmode==3 && DirectX7.fDist==22000 && DirectX7.drDist==30000 && DirectX7.fogc==0x2f1028,"sample lost level globals");
            int world=0,mesh=0,sprite=0,background=0,triangles=0;
            for(Graphics3D.Draw d:Graphics3D.history) {
                String kind=d.source==null?"sky":nativeNames.get(d.source);check(kind!=null,"missing native texture attribution");
                if(kind.equals("world") || kind.equals("mesh")) {
                    check(d.fog!=FOG_OFF,"world/mesh lost configured native Fog");
                    if(!FOG_OFF)check(d.fogMode==javax.microedition.m3g.Fog.LINEAR,"mode3 not LINEAR");
                }
                if(kind.equals("world"))world++;else if(kind.equals("mesh"))mesh++;
                else if(kind.equals("sprite"))sprite++;else background++;
                if(d.cull==javax.microedition.m3g.PolygonMode.CULL_BACK)triangles+=d.indices.length/3;
            }
            check(world+mesh+sprite+background==g.getM3GDrawCalls(),"draw attribution mismatch");
            if(frame==3)check(g.getM3GTextureUploads()==0,"warm snapshot reuploaded textures");
            if(frame==0 || frame==3)System.out.println("L13 part="+part+" fog="+(FOG_OFF?"off":"on")+" sample="+label+" phase="+(frame==0?"cold":"warm")
                +" room="+room+" pos="+cam.m03+","+cam.m13+","+cam.m23+" yaw="+yaw+" pitch="+pitch+" projected="+projected+" queued="+queued
                +" D="+g.getM3GDrawCalls()+" sky="+background+" world="+world+" mesh="+mesh+" sprites="+sprite+" triangles="+triangles
                +" B="+g.getM3GCpuBarriers()+" T="+g.getM3GTextureUploads()+" readbacks="+(Image.getRGBCalls-reads)+" copies="+(Graphics.drawRGBCalls-copies)
                +" textureSplits="+WorldBottleneckProbe.textureSplits+" stateSplits="+WorldBottleneckProbe.stateSplits+" scaleSplits="+WorldBottleneckProbe.scaleSplits+" capacitySplits="+WorldBottleneckProbe.capacitySplits
                +" fogShared="+WorldBottleneckProbe.fogShared+" fogLegacy="+WorldBottleneckProbe.fogLegacy+" clipAttempts="+WorldBottleneckProbe.clipAttempts+" clipRecovered="+WorldBottleneckProbe.clipRecovered+" clipTriangles="+WorldBottleneckProbe.clipTriangles
                +" stream="+stream()+" rejects="+WorldBottleneckProbe.rejects+" packRejects="+WorldBottleneckProbe.packRejects);
        }
        active=counting=WorldBottleneckProbe.active=false;g.destroy();Graphics3D.history.clear();nativeNames.clear();nativeSources.clear();
    }
    private static void hashInt(MessageDigest hash,int n) {
        hash.update((byte)(n>>>24));hash.update((byte)(n>>>16));hash.update((byte)(n>>>8));hash.update((byte)n);
    }
    // Packet-boundary-independent witness of every submitted triangle. For Fog,
    // hash physical XYZ bits; otherwise hash screen XY and relative W (the
    // per-polygon homogeneous scale cancels). Source labels include cache kind.
    private static String stream() throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(Graphics3D.Draw d:Graphics3D.history) {
            String state=(d.source==null?"untextured":nativeSources.get(d.source))+":"+d.blend+":"+d.function+":"+d.blendColor
                +":"+d.filter+":"+d.threshold+":"+d.cull+":"+d.winding+":"+d.perspective+":"+d.fog+":"+d.fogMode
                +":"+d.fogColor+":"+d.near+":"+d.far+":"+d.density+":"+d.colorComponents+":"+d.lit+":"+d.lights;
            byte[] material=state.getBytes("UTF-8");int offset=0;
            for(int length:d.lengths) {
                for(int i=2;i<length;i++) {
                    int a=d.indices[offset+i-2],b=d.indices[offset+i-1],c=d.indices[offset+i];
                    if((i&1)!=0){int t=a;a=b;b=t;}
                    hash.update(material);for(float v:d.projection)hashInt(hash,Float.floatToIntBits(v));
                    for(int v:new int[]{a,b,c}) {
                        if(d.fog)for(int k=0;k<3;k++)hashInt(hash,Float.floatToIntBits(d.positions[v*3+k]*d.scale));
                        else {
                            double z=-d.positions[v*3+2];
                            for(double value:new double[]{d.positions[v*3]/z,d.positions[v*3+1]/z,z/-d.positions[a*3+2]}) {
                                long bits=Double.doubleToLongBits(value);hashInt(hash,(int)(bits>>>32));hashInt(hash,(int)bits);
                            }
                        }
                        if(d.textured)for(int k=0;k<2;k++)hashInt(hash,Float.floatToIntBits(d.coords[v*2+k]*d.uvScale));
                        for(int k=0;k<d.colorComponents;k++)hash.update(d.colors[v*d.colorComponents+k]);
                    }
                }
                offset+=length;
            }
        }
        byte[] digest=hash.digest();StringBuffer hex=new StringBuffer();
        for(byte b:digest){int n=b&255;if(n<16)hex.append('0');hex.append(Integer.toHexString(n));}return hex.toString();
    }
    public static void main(String[] args) throws Exception {
        String file=text("/level13.txt");Method cut=LevelLoader.class.getDeclaredMethod("cutLevelFile",String.class);cut.setAccessible(true);
        GameIni level=new GameIni(((String[])cut.invoke(null,file))[0],false);Main.settings=new GameIni(text("/setting.txt"),true);
        check("/gamedata/maps/kordon/kordon.3d".equals(level.get("WORLD_MODEL")),"wrong level13 archive");
        Main.pixelsQ=2;Main.persQ=4;Main.fogQ=FOG_OFF?0:2;Main.mipMapping=false;Main.m3gPerspective=true;Main.floorOffsetSZ=0;Main.isSounds=false;
        DirectX7.standartDrawmode=(byte)level.getInt("DMODE",0);DirectX7.setDrDist(level.getInt("DIST",Integer.MAX_VALUE));DirectX7.setFogDist(level.getInt("FOGD",1));
        DirectX7.fogc=level.getInt("FOGCR",0)<<16|level.getInt("FOGCG",0)<<8|level.getInt("FOGCB",0);
        levelConfig=level;seedImages(args[0]);code.AI.misc.Blood.blood=Asset.getTexture(Main.settings.get("BLOOD_SPRITE"));MultyTexture t=new MultyTexture(level.get("WORLD_TEXTURE"),true);
        check(t.textures.length==2 && t.textures[0].rImg==t.textures[1].rImg,"world texture alias not shared");
        Mesh[] meshes=decode(level.get("WORLD_MODEL"),level.getFloat("WORLD_SCALE",1),t);
        Room.chunkSize=5000;Room.chunkSizeRender=level.getInt("CHUNK_SIZE",Main.settings.getInt("CHUNK_SIZE",0));
        House house=HouseCreator.create(meshes,false,null,level.getInt("OPTIMIZE_LEVEL_MODEL",1)==1);
        int vertices=0,polygons=0,triangles=0;int[] materials=new int[2];
        for(Room r:house.getRooms()) {
            Mesh m=r.getMesh();vertices+=m.getVertices().length;polygons+=m.getPolygons().length;
            for(RenderObject p:m.getPolygons()){triangles+=p instanceof Polygon3V?1:2;materials[p instanceof Polygon3V?((Polygon3V)p).tex:((Polygon4V)p).tex]++;}
        }
        System.out.println("L13 world rooms="+house.getRooms().length+" vertices="+vertices+" polygons="+polygons+" triangles="+triangles+" materials="+Arrays.toString(materials)+" chunk="+Room.chunkSizeRender+" dmode="+DirectX7.standartDrawmode+" dist="+DirectX7.drDist+" fogd="+DirectX7.fDist);
        String[] p=level.get("SKYBOX_2D").split(",");byte mode=DirectX7.standartDrawmode;DirectX7.standartDrawmode=0;
        Skybox sky=new Skybox(Asset.getTexture(p[0]),level.getFloat("SKYBOX_REPEAT_X",2),level.getFloat("SKYBOX_REPEAT_Y",1),
            Integer.parseInt(p[2]),Float.parseFloat(p[1]),Float.parseFloat(p[3]),0,0x2f1028);
        DirectX7.standartDrawmode=mode;sky.skyboxAlways=true;house.setSkybox(sky);
        loadObjects(house,level,file);
        check(level.get("WEATHER")==null && Main.settings.get("WEATHER")==null,"unexpected weather; probe must implement actual configuration");
        Map<RawImage,int[]> hashes=new IdentityHashMap<RawImage,int[]>();for(RawImage raw:names.keySet())hashes.put(raw,raw.img.clone());
        String[] labels={"start","camp","rzavy","bandits","soldiers"};
        int[][] feet={pos(level.get("START")),{-110001,-3757,-115102},{-104423,-3676,-128113},{-79398,-3176,-20632},{-32789,936,-8797}};
        for(int i=0;i<feet.length;i++)for(int pitch:new int[]{0,45})for(int yaw=0;yaw<360;yaw+=45)
            for(String part:FOG_OFF?new String[]{"objects"}:new String[]{"world","objects"})sample(house,sky,labels[i],feet[i],yaw,pitch,part);
        for(RawImage raw:names.keySet())check(Arrays.equals(hashes.get(raw),raw.img),"original pixels mutated");
        house.destroy();System.out.println("PASS level13 actual world+panorama / 5 meshes+23 sprites+5 idle NPC / no weather / 80 poses x cold+warm / optional Fog OFF is diagnostic only / source pixels unchanged (not FPS/gameplay)");
    }
}
