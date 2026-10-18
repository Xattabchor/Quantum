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

/** Stlkr level12/13 snapshot diagnostic on current M3G. Not AI/gameplay/GPU timing.
 * Real decoders, House/portals, MeshObject/SpriteObject and NPC idle render.
 * Uploaded archive classes are excluded; PNG fixture decoding uses ImageIO.
 */
public final class Level13Probe {
    public static boolean counting;
    public static int projected;
    private static boolean active;
    private static final int LEVEL=Integer.getInteger("level.probe",13);
    private static final String TAG="L"+LEVEL;
    private static final Set<RawImage> worldImages=Collections.newSetFromMap(new IdentityHashMap<RawImage,Boolean>());
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
    private static final Map<Texture2D,Boolean> opacity=new IdentityHashMap<Texture2D,Boolean>();
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
                worldImages.contains(raw)?"world":"mesh");
        }
    }
    private static InputStream resource(String n) {
        InputStream in=Level13Probe.class.getResourceAsStream(n);
        if(in==null)throw new IllegalArgumentException("Missing Stlkr resource: "+n);return in;
    }
    static String text(String n) throws Exception {
        try(InputStream in=resource(n)) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int k;
            while((k=in.read(bytes))!=-1)b.write(bytes,0,k);return new String(b.toByteArray(),"windows-1251").replace("\r","");
        }
    }
    static Mesh[] decode(String n,float scale,MultyTexture t) throws Exception {
        Method m=Room.class.getDeclaredMethod("createFrom3d",String.class,DataInputStream.class,
            Float.TYPE,Float.TYPE,Float.TYPE,MultyTexture.class);m.setAccessible(true);
        try(DataInputStream in=new DataInputStream(resource(n))) {
            Mesh[] meshes=new Mesh[in.readInt()];
            for(int i=0;i<meshes.length;i++)meshes[i]=(Mesh)m.invoke(null,n,in,scale,scale,scale,t);
            check(in.available()==0,"unconsumed model data "+n);return meshes;
        }
    }
    static void seedImages(String jar) throws Exception {
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
        System.out.println(TAG+" fixture PNGs="+names.size());
    }
    private static int[] pos(String s){return GameIni.createPos(s,',');}
    private static void add(House house,RoomObject o,GameIni obj,GameIni level) {
        LevelLoader.loadRM(obj,o,level,obj.get("PRESET"));house.addObject(o);
        check(o.getPart()!=-1,"render object outside House "+o.getPosX()+","+o.getPosZ());objects.add(o);
    }
    private static String setting(GameIni obj,String name,String fallback) {
        String key=obj.get("PRESET");
        return obj.getDef(name,key==null?Main.settings.getDef(name,fallback):Main.settings.getDef(key,name,Main.settings.getDef(name,fallback)));
    }
    private static ScreenLines rain(DirectX7 g) {
        check("RAIN".equals(levelConfig.get("WEATHER")),"expected level12 rain");
        WeatherGenerator w=new WeatherGenerator(g.width,g.height,levelConfig.getInt("WEATHER_PARTICLES_COUNT",170),
            0xaabbdd,0x666666,0xffffff,0xcaccdf,false,null,4,levelConfig.getInt("WEATHER_PARTICLES_SIZE",22),120,160,2,10);
        check(levelConfig.get("WEATHER_COLOR_NEAR")==null && levelConfig.get("WEATHER_COLOR_FAR")==null
            && Main.settings.get("WEATHER_COLOR_NEAR")==null && Main.settings.get("WEATHER_COLOR_FAR")==null,"custom rain colors need fixture support");
        w.generate=1;w.setRenderer(DirectX7.M3G);
        // Deterministic active rain snapshot, not random simulation/roof/lightning.
        for(int i=0;i<w.info.length/2;i++){w.info[i*2]=1;w.info[i*2+1]=10+i*8;w.coords[i*2]=20+i*19;w.coords[i*2+1]=25+i*21;}
        return w.prepareM3G(g);
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
                    MultyTexture t=new MultyTexture(LEVEL==12?setting(obj,"TEX",null):obj.get("TEX"),false);
                    float scale=LEVEL==12?Float.parseFloat(setting(obj,"SCALE",setting(obj,"MODEL_SCALE","1"))):obj.getFloat("SCALE",1);
                    MeshObject m=new MeshObject(decode(LEVEL==12?setting(obj,"MODEL",null):obj.get("MODEL"),scale,t),t,p[0],p[1],p[2],false);
                    if(LEVEL==12)Asset.applyMeshEffects(m.animation.getMesh(),setting(obj,"DRAW_MODES",null));
                    m.getCharacter().getTransform().rotY(obj.getInt("ROT",0));m.addsz=LEVEL==12?Integer.parseInt(setting(obj,"ADDSZ",String.valueOf(m.addsz))):obj.getInt("ADDSZ",m.addsz);
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
        System.out.println(TAG+" objects mesh="+meshCount+" sprite="+spriteCount+" idleNPC="+npcCount+" inactiveSpawnerGroups="+spawnerGroups+" total="+objects.size());
        check(LEVEL==13?meshCount==5 && spriteCount==23 && npcCount==5 && spawnerGroups==4:
            meshCount==6 && spriteCount==1 && npcCount==5 && spawnerGroups==1,"level object inventory changed");
    }
    private static void resetReasons() {
        WorldBottleneckProbe.active=true;
        WorldBottleneckProbe.textureSplits=WorldBottleneckProbe.stateSplits=WorldBottleneckProbe.scaleSplits=WorldBottleneckProbe.capacitySplits=WorldBottleneckProbe.scaleOnlySplits=0;
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
        nativeNames.clear();nativeSources.clear();opacity.clear();active=true;
        for(int frame=0;frame<4;frame++) {
            Graphics3D.history.clear();g.beginFrameStats();resetReasons();counting=true;
            int reads=Image.getRGBCalls,copies=Graphics.drawRGBCalls;
            // Fixed idle snapshot: restart sprite animations just before the
            // real render, so the fire/idle animation begins at frame zero.
            for(Sprite s:sprites){s.animationBegin=System.currentTimeMillis();s.anim_index=0;}
            sky.draw=true;sky.addViewport(0,0,g.width,g.height);
            house.render(null,g,House.getParts(house,room,cam),feet[0],feet[2]);
            int queued=((RenderObjectBuffer)q.get(g)).getSize();
            g.renderGameFrameImage(null,false,false,house,room,LEVEL==12?rain(g):null);counting=false;
            check(g.isM3GActive(),"Stlkr native backend failed: "+g.getRendererError());
            check(DirectX7.standartDrawmode==3 && DirectX7.fDist==levelConfig.getInt("FOGD",1) && DirectX7.drDist==levelConfig.getInt("DIST",1),"sample lost level globals");
            int world=0,mesh=0,sprite=0,background=0,weatherDraws=0,fogSolid=0,triangles=0;
            for(Graphics3D.Draw d:Graphics3D.history) {
                String kind=d.source==null && d.fog?"fogSolid":d.source==null?(LEVEL==12 && d.cull==javax.microedition.m3g.PolygonMode.CULL_BACK?"mesh":
                    LEVEL==12 && d.colorComponents==3?"weather":"sky"):nativeNames.get(d.source);check(kind!=null,"missing native texture attribution");
                if(kind.equals("world") || (LEVEL==13 && kind.equals("mesh"))) {
                    check(d.fog!=FOG_OFF,"world/mesh lost configured native Fog");
                    if(!FOG_OFF)check(d.fogMode==javax.microedition.m3g.Fog.LINEAR,"mode3 not LINEAR");
                }
                if(kind.equals("world"))world++;else if(kind.equals("mesh"))mesh++;
                else if(kind.equals("fogSolid"))fogSolid++;else if(kind.equals("sprite"))sprite++;else if(kind.equals("weather"))weatherDraws++;else background++;
                if(d.cull==javax.microedition.m3g.PolygonMode.CULL_BACK)triangles+=d.indices.length/3;
            }
            check(world+mesh+sprite+background+weatherDraws+fogSolid==g.getM3GDrawCalls(),"draw attribution mismatch");
            if(frame==3)check(g.getM3GTextureUploads()==0,"warm snapshot reuploaded textures");
            if(frame==0 || frame==3)System.out.println(TAG+" part="+part+" fog="+(FOG_OFF?"off":"on")+" sample="+label+" phase="+(frame==0?"cold":"warm")
                +" room="+room+" pos="+cam.m03+","+cam.m13+","+cam.m23+" yaw="+yaw+" pitch="+pitch+" projected="+projected+" queued="+queued
                +" D="+g.getM3GDrawCalls()+" sky="+background+" world="+world+" mesh="+mesh+" sprites="+sprite+" triangles="+triangles
                +(LEVEL==12?" weather="+weatherDraws:"")+" fogSolid="+fogSolid+" B="+g.getM3GCpuBarriers()+" T="+g.getM3GTextureUploads()+" readbacks="+(Image.getRGBCalls-reads)+" copies="+(Graphics.drawRGBCalls-copies)
                +" textureSplits="+WorldBottleneckProbe.textureSplits+" stateSplits="+WorldBottleneckProbe.stateSplits+" scaleSplits="+WorldBottleneckProbe.scaleSplits+" capacitySplits="+WorldBottleneckProbe.capacitySplits+(LEVEL==12?" scaleOnlySplits="+WorldBottleneckProbe.scaleOnlySplits:"")
                +" fogShared="+WorldBottleneckProbe.fogShared+" fogLegacy="+WorldBottleneckProbe.fogLegacy+" clipAttempts="+WorldBottleneckProbe.clipAttempts+" clipRecovered="+WorldBottleneckProbe.clipRecovered+" clipTriangles="+WorldBottleneckProbe.clipTriangles
                +" stream="+stream(false)+" visibleStream="+stream(true)+" rejects="+WorldBottleneckProbe.rejects+" packRejects="+WorldBottleneckProbe.packRejects);
        }
        active=counting=WorldBottleneckProbe.active=false;g.destroy();Graphics3D.history.clear();nativeNames.clear();nativeSources.clear();
    }
    private static void hashInt(MessageDigest hash,int n) {
        hash.update((byte)(n>>>24));hash.update((byte)(n>>>16));hash.update((byte)(n>>>8));hash.update((byte)n);
    }
    // Packet-boundary-independent witness of every submitted triangle. For Fog,
    // hash physical XYZ bits; otherwise hash screen XY and relative W (the
    // per-polygon homogeneous scale cancels). Source labels include cache kind.
    // Independent result witness: a LINEAR fully opaque triangle beyond far
    // has constant Fog RGB. Canonicalize ONLY those, hashing exact XY/W/UV/RGBA
    // and color. All other triangles keep the strict physical depth witness.
    private static boolean constantFog(Graphics3D.Draw d,int a,int b,int c) {
        if(!d.fog || d.fogMode!=javax.microedition.m3g.Fog.LINEAR || d.lit
            || d.threshold!=0 || d.blend!=javax.microedition.m3g.CompositingMode.REPLACE
            || (d.source!=null && d.function!=Texture2D.FUNC_MODULATE))return false;
        if(d.source!=null) {
            Boolean value=opacity.get(d.source);
            if(value==null) {
                boolean all=true;byte[] pixels=d.source.image.pixels;
                for(int i=3;i<pixels.length;i+=4)if((pixels[i]&255)!=255){all=false;break;}
                value=Boolean.valueOf(all);opacity.put(d.source,value);
            }
            if(!value.booleanValue())return false;
        }
        for(int v:new int[]{a,b,c})
            if(-d.positions[v*3+2]*(double)d.scale<=Math.max(d.far,0.01)+0.005
                || (d.colors[v*d.colorComponents+d.colorComponents-1]&255)!=255)return false;
        return true;
    }
    private static String stream(boolean visible) throws Exception {
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
                    boolean constant=visible && constantFog(d,a,b,c);
                    hash.update(constant?("constantFog:"+d.fogColor+":"+d.cull+":"+d.winding+":"+d.perspective+":"+d.colorComponents).getBytes("UTF-8"):material);
                    for(float v:d.projection)hashInt(hash,Float.floatToIntBits(v));
                    for(int v:new int[]{a,b,c}) {
                        if(d.fog && !constant)for(int k=0;k<3;k++)hashInt(hash,Float.floatToIntBits(d.positions[v*3+k]*d.scale));
                        else {
                            double z=-d.positions[v*3+2];
                            for(double value:new double[]{d.positions[v*3]/z,d.positions[v*3+1]/z,z/-d.positions[a*3+2]}) {
                                long bits=Double.doubleToLongBits(value);hashInt(hash,(int)(bits>>>32));hashInt(hash,(int)bits);
                            }
                        }
                        if(d.textured || constant)for(int k=0;k<2;k++)hashInt(hash,Float.floatToIntBits(d.coords[v*2+k]*d.uvScale));
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
        NativeFallbackAudit.begin("objects-level"+LEVEL);
        String file=text("/level"+LEVEL+".txt");Method cut=LevelLoader.class.getDeclaredMethod("cutLevelFile",String.class);cut.setAccessible(true);
        GameIni level=new GameIni(((String[])cut.invoke(null,file))[0],false);Main.settings=new GameIni(text("/setting.txt"),true);
        check(LEVEL==12 || LEVEL==13,"unsupported Stlkr level");
        check(level.get("WORLD_MODEL").equals(LEVEL==12?"/gamedata/maps/pripyat/pripyat.3d":"/gamedata/maps/kordon/kordon.3d"),"wrong Stlkr archive");
        Main.pixelsQ=2;Main.persQ=4;Main.fogQ=FOG_OFF?0:2;Main.mipMapping=false;Main.m3gPerspective=true;Main.floorOffsetSZ=0;Main.isSounds=false;
        DirectX7.standartDrawmode=(byte)level.getInt("DMODE",0);DirectX7.setDrDist(level.getInt("DIST",Integer.MAX_VALUE));DirectX7.setFogDist(level.getInt("FOGD",1));
        DirectX7.fogc=level.getInt("FOGCR",0)<<16|level.getInt("FOGCG",0)<<8|level.getInt("FOGCB",0);
        levelConfig=level;seedImages(args[0]);code.AI.misc.Blood.blood=Asset.getTexture(Main.settings.get("BLOOD_SPRITE"));MultyTexture t=new MultyTexture(level.get("WORLD_TEXTURE"),true);
        if(LEVEL==13)check(t.textures.length==2 && t.textures[0].rImg==t.textures[1].rImg,"world texture alias not shared");
        for(Texture texture:t.textures)worldImages.add(texture.rImg);
        Mesh[] meshes=decode(level.get("WORLD_MODEL"),level.getFloat("WORLD_SCALE",1),t);
        Room.chunkSize=5000;Room.chunkSizeRender=level.getInt("CHUNK_SIZE",Main.settings.getInt("CHUNK_SIZE",0));
        House house=HouseCreator.create(meshes,false,null,level.getInt("OPTIMIZE_LEVEL_MODEL",1)==1);
        int vertices=0,polygons=0,triangles=0;int[] materials=new int[t.textures.length];
        for(Room r:house.getRooms()) {
            Mesh m=r.getMesh();vertices+=m.getVertices().length;polygons+=m.getPolygons().length;
            for(RenderObject p:m.getPolygons()){triangles+=p instanceof Polygon3V?1:2;materials[p instanceof Polygon3V?((Polygon3V)p).tex:((Polygon4V)p).tex]++;}
        }
        if(LEVEL==12)check(house.getRooms().length==9 && polygons==1621 && triangles==3178 && t.textures.length==3,"level12 map inventory changed");
        System.out.println(TAG+" world rooms="+house.getRooms().length+" vertices="+vertices+" polygons="+polygons+" triangles="+triangles+" materials="+Arrays.toString(materials)+" chunk="+Room.chunkSizeRender+" dmode="+DirectX7.standartDrawmode+" dist="+DirectX7.drDist+" fogd="+DirectX7.fDist);
        String[] p=level.get("SKYBOX_2D").split(",");byte mode=DirectX7.standartDrawmode;DirectX7.standartDrawmode=0;
        Skybox sky=new Skybox(Asset.getTexture(p[0]),level.getFloat("SKYBOX_REPEAT_X",2),level.getFloat("SKYBOX_REPEAT_Y",1),
            Integer.parseInt(p[2]),Float.parseFloat(p[1]),Float.parseFloat(p[3]),0,DirectX7.fogc);
        DirectX7.standartDrawmode=mode;sky.skyboxAlways=true;house.setSkybox(sky);
        loadObjects(house,level,file);
        if(LEVEL==13)check(level.get("WEATHER")==null && Main.settings.get("WEATHER")==null,"unexpected weather; probe must implement actual configuration");
        Map<RawImage,int[]> hashes=new IdentityHashMap<RawImage,int[]>();for(RawImage raw:names.keySet())hashes.put(raw,raw.img.clone());
        String[] labels={"start","camp","rzavy","bandits","soldiers"};
        int[][] feet={pos(level.get("START")),{-110001,-3757,-115102},{-104423,-3676,-128113},{-79398,-3176,-20632},{-32789,936,-8797}};
        if(LEVEL==12) {
            labels=new String[]{"start","north-zombie","central-zombie","south-zombie","helicopter"};
            feet=new int[][]{pos(level.get("START")),{1607,0,-35579},{34507,0,6122},{76610,0,-36271},{5676,0,13025}};
        }
        for(int i=0;i<feet.length;i++)for(int pitch:new int[]{0,45})for(int yaw=0;yaw<360;yaw+=45)
            for(String part:FOG_OFF?new String[]{"objects"}:new String[]{"world","objects"})sample(house,sky,labels[i],feet[i],yaw,pitch,part);
        for(RawImage raw:names.keySet())check(Arrays.equals(hashes.get(raw),raw.img),"original pixels mutated");
        NativeFallbackAudit.end();house.destroy();System.out.println("PASS level"+LEVEL+" actual world+panorama / fixed idle objects / configured weather snapshot / 80 poses x cold+warm / optional Fog OFF is diagnostic only / source pixels unchanged (not FPS/gameplay)");
    }
}
