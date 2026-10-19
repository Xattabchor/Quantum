package code.Rendering;

import code.Gameplay.Map.Room;
import code.Math.Matrix;
import code.Rendering.Meshes.*;
import code.Rendering.Meshes.Mesh;
import code.utils.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.List;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.*;

/** Exact ordered native strip/attribute/state comparison; NOT a rasterizer. */
public final class StaticRoomLayoutsTest {
    static long checks;static int cases,water,baseCalls,smallCalls;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
    static Mesh load()throws Exception {
        Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.fogQ=2;Main.mipMapping=false;Main.m3gPerspective=true;
        String text=StringTools.getStringFromResource("/level1.txt"),begin="[WTEX_BEGIN]",end="[WTEX_END]";int a=text.indexOf(begin),b=text.indexOf(end);
        String[] lines=text.substring(a+begin.length(),b).trim().split("\\r?\\n");MultyTexture textures=new MultyTexture(lines);
        GameIni config=new GameIni(text.substring(0,a)+text.substring(b+end.length()),true);DirectX7.standartDrawmode=(byte)config.getInt("DMODE",9);
        float scale=config.getFloat("WORLD_SCALE",1);Mesh[] meshes=Room.loadMeshes(config.get("WORLD_MODEL"),scale,scale,scale,textures);
        StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomLayoutsTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomLayoutsTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"A changed call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors),"A packed geometry differs from immutable Tex3");
            ok(Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths),"A selected strip/index stream changed");
            ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"A packing changed");
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"A state/projection changed");
            ok(Arrays.equals(x.source.image.pixels,y.source.image.pixels),"A texture/river changed");
        }
    }
    static void sameStream(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        int parent=0,face=0,offset=0;
        for(Graphics3D.Draw y:b) {
            int childOffset=0;
            for(int length:y.lengths) {
                while(parent<a.size() && face==a.get(parent).lengths.length){parent++;face=offset=0;}
                ok(parent<a.size(),"B submitted extra faces");Graphics3D.Draw x=a.get(parent);eq(length,x.lengths[face],"face/strip order changed");
                ok(x.source==y.source && Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"B material/state/projection/order changed");
                ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"B requantized positions/UV");
                for(int j=0;j<length;j++) {
                    int ix=x.indices[offset+j],iy=y.indices[childOffset+j];ok(iy>=0 && iy<y.positionCapacity,"B local index invalid");
                    for(int k=0;k<3;k++)eq(x.positions[ix*3+k],y.positions[iy*3+k],"B XYZ changed");
                    for(int k=0;k<2;k++)eq(x.coords[ix*2+k],y.coords[iy*2+k],"B UV changed");
                    for(int k=0;k<4;k++)eq(x.colors[ix*4+k],y.colors[iy*4+k],"B baked RGBA changed");
                }
                if(y.blend==CompositingMode.MODULATE)water++;
                offset+=length;childOffset+=length;face++;
            }
            eq(childOffset,y.indices.length,"unconsumed B indices");
        }
        while(parent<a.size() && face==a.get(parent).lengths.length){parent++;face=offset=0;}
        eq(parent,a.size(),"B lost source faces");
    }
    static int capacity(List<Graphics3D.Draw> draws){int n=0;for(Graphics3D.Draw d:draws)n+=d.positionCapacity;return n;}
    static void matched(List<Graphics3D.Draw> b,List<Graphics3D.Draw> c) {
        eq(b.size(),c.size(),"B/C calls differ");
        for(int i=0;i<b.size();i++) {
            Graphics3D.Draw x=b.get(i),y=c.get(i);ok(Arrays.equals(x.lengths,y.lengths),"B/C strip batches differ");
            eq(x.indices.length,y.indices.length,"B/C index count");ok(y.positionCapacity<=512,"C buffer cap");ok(y.positionCapacity<=x.positionCapacity,"C capacity larger than B");
            int delta=x.indices[0]-y.indices[0];
            for(int j=0;j<x.indices.length;j++)eq(x.indices[j]-y.indices[j],delta,"B/C not constant-offset indices");
        }
    }
    static void uniqueness(StaticRoomLayoutsMesh room)throws Exception {
        Vector parts=(Vector)get(room,"parts");int total=0;
        for(Object p:parts) {
            ok(get(p,"packedXYZ")==null && get(p,"packedColor")==null,"CPU staging retained");
            VertexBuffer a=(VertexBuffer)get(p,"vertices"),d=(VertexBuffer)get(p,"sharedVertices");int n=(Integer)get(p,"vertexCount");int[] map=(int[])get(p,"sharedMap");
            Map<String,Integer> expected=new HashMap<String,Integer>();
            for(int i=0;i<n;i++) {
                String key="";for(int k=0;k<3;k++)key+=a.positions.shorts[i*3+k]+",";
                for(int k=0;k<2;k++)key+=a.coords.shorts[i*2+k]+",";
                for(int k=0;k<4;k++)key+=a.colors.bytes[i*4+k]+",";
                Integer id=expected.get(key);if(id==null){id=expected.size();expected.put(key,id);}eq(map[i],id,"not exact first-occurrence tuple map");
                for(int k=0;k<3;k++)eq(a.positions.shorts[i*3+k],d.positions.shorts[id*3+k],"shared cold XYZ");
                for(int k=0;k<2;k++)eq(a.coords.shorts[i*2+k],d.coords.shorts[id*2+k],"shared cold UV");
                for(int k=0;k<4;k++)eq(a.colors.bytes[i*4+k],d.colors.bytes[id*4+k],"shared cold RGBA");
            }
            eq(expected.size(),(Integer)get(p,"sharedCount"),"unique count");total+=expected.size();
        }
        eq(total,room.sharedUploadedVertices,"shared resident count");ok(total<room.uploadedVertices,"city has no actual sharing benefit");
    }
    static List<Graphics3D.Draw> render(StaticRoomLayoutsMesh room,int mode,Graphics target,Matrix pose) {
        room.setLayout(mode);Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);ok(Graphics3D.target==null,"bound target leak");return capture();
    }
    static void sweep(Mesh source)throws Exception {
        StaticRoomBvhMesh baseline=new StaticRoomBvhMesh(source,2,true);baseline.setNearPlane(128);baseline.setCullingEnabled(true);
        StaticRoomLayoutsMesh room=new StaticRoomLayoutsMesh(source,2,true);room.setNearPlane(128);room.setCullingEnabled(true);uniqueness(room);
        eq(room.getLayout(),0,"not original default");eq(room.uploadedVertices,10032,"parent vertices");
        Texture river=source.getTexture().textures[2];RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,320).getGraphics();int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(boolean bvh:new boolean[]{false,true})for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];baseline.setBvhEnabled(bvh);room.setBvhEnabled(bvh);
            Graphics3D.history.clear();baseline.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> original=capture();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            List<Graphics3D.Draw> a=render(room,0,target,pose);sameA(original,a);
            int triangles=room.submittedTriangles,corners=room.submittedCorners,tests=room.visibilityTests;
            eq(triangles,baseline.submittedTriangles,"A triangles differ");eq(corners,baseline.submittedCorners,"A corners differ");
            List<Graphics3D.Draw> b=render(room,1,target,pose);sameStream(a,b);eq(room.submittedBufferVertices,capacity(b),"B capacity counter");
            int bcIndices=TriangleStripArray.created;
            List<Graphics3D.Draw> c=render(room,2,target,pose);sameStream(a,c);matched(b,c);eq(TriangleStripArray.created,bcIndices,"switch B/C reallocated indices");
            eq(room.submittedBufferVertices,capacity(c),"C capacity counter");ok(capacity(c)<=capacity(a),"C capacity larger than A");
            List<Graphics3D.Draw> d=render(room,3,target,pose);sameStream(a,d);eq(d.size(),a.size(),"A/D calls differ");eq(room.submittedBufferVertices,capacity(d),"D capacity counter");ok(capacity(d)<=capacity(a),"D capacity larger than A");
            for(int i=0;i<a.size();i++)ok(Arrays.equals(a.get(i).lengths,d.get(i).lengths),"A/D strip batches differ");
            eq(room.submittedTriangles,triangles,"D triangles changed");eq(room.submittedCorners,corners,"D corners changed");eq(room.visibilityTests,tests,"selector changed");ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera changed");
            int indices=TriangleStripArray.created;
            for(int mode=0;mode<4;mode++){render(room,mode,target,pose);eq(room.submittedTriangles,triangles,"cycle triangles changed");eq(room.submittedCorners,corners,"cycle corners changed");}
            eq(TriangleStripArray.created,indices,"warm cycle index allocation");eq(VertexArray.created,arrays,"hot vertex allocation");eq(VertexArray.sets,sets,"hot vertex upload");eq(Graphics3D.uploads,uploads,"hot texture upload");
            if(!bvh && point==points[0] && pitch==0 && yaw==0 && frame==0)System.out.println("CONTROL geometry only: T="+triangles+" A DC="+a.size()+" VB="+capacity(a)+"; B DC="+b.size()+" VB="+capacity(b)+"; C DC="+c.size()+" VB="+capacity(c)+"; D DC="+d.size()+" VB="+capacity(d)+"; shared resident="+room.sharedUploadedVertices+" (NOT phone FPS)");
            cases++;
        }
        river.rImg=frames[0];room.dispose();baseline.dispose();System.out.println("PASS "+cases+" cases: ordered A/B/C/D streams and A/immutable Tex3, both selectors/all4 river frames; "+water+" river strip comparisons");
    }
    static Texture texture(){Texture t=new Texture(new RawImage(new int[]{0,-1,-1,-1},2,2),true,null);t.drawmode=9;return t;}
    static LightedPolygon3V face(int x,int y) {
        LightedPolygon3V p=new LightedPolygon3V(new Vertex(x,y,-1200),new Vertex(x+1200,y,-1200),new Vertex(x,y+600,-1800),(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);p.la=p.lb=p.lc=100;return p;
    }
    static Mesh tiny(){return new Mesh(new Vertex[0],new RenderObject[]{face(0,0),face(0,0)},new MultyTexture(texture()));}
    static void seamsAndFailures(Mesh source)throws Exception {
        LightedPolygon3V a=face(0,0),duplicate=face(0,0),uv=face(0,0),color=face(0,0),collision=face(1,-31);uv.au=1;color.la=200;
        Mesh data=new Mesh(new Vertex[0],new RenderObject[]{a,duplicate,uv,color,collision},new MultyTexture(texture()));
        StaticRoomLayoutsMesh room=new StaticRoomLayoutsMesh(data,2,true);Object p=((Vector)get(room,"parts")).elementAt(0);int[] map=(int[])get(p,"sharedMap");
        eq(map[0],map[3],"exact duplicate not shared");ok(map[0]!=map[6],"UV seam merged");ok(map[0]!=map[9],"baked color seam merged");ok(map[0]!=map[12],"distinct collision position merged");
        Method hash=StaticRoomLayoutsMesh.class.getDeclaredMethod("cornerHash",short[].class,short[].class,byte[].class,int.class);hash.setAccessible(true);
        short[] xyz={0,0,0,1,-31,0},coords=new short[4];byte[] rgba=new byte[8];
        eq((Integer)hash.invoke(null,xyz,coords,rgba,0),(Integer)hash.invoke(null,xyz,coords,rgba,1),"fixture not a true hash collision");
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();List<Graphics3D.Draw> expected=render(room,0,target,pose);sameStream(expected,render(room,3,target,pose));room.dispose();
        // Optional D failure must preserve complete A and the independent B/C.
        for(int kind=0;kind<2;kind++) {
            if(kind==0)VertexArray.failAt=VertexArray.created+4;else TriangleStripArray.failAt=TriangleStripArray.created+2;
            try{room=new StaticRoomLayoutsMesh(tiny(),2,true);}finally{VertexArray.failAt=TriangleStripArray.failAt=0;}
            ok(!room.isLayoutAvailable(3) && room.isLayoutAvailable(0) && room.isLayoutAvailable(2),"shared OOM disabled unrelated layouts");eq(room.sharedUploadedVertices,0,"failed shared resident refs");render(room,0,target,pose);render(room,2,target,pose);
            boolean failed=false;try{room.setLayout(3);}catch(IllegalStateException ex){failed=true;}ok(failed,"disabled shared silently drawn as A");room.dispose();
        }
        // A=3 VAs, D=3 VAs; next constructor belongs to optional compact C.
        VertexArray.failAt=VertexArray.created+7;try{room=new StaticRoomLayoutsMesh(tiny(),2,true);}finally{VertexArray.failAt=0;}
        ok(!room.isLayoutAvailable(1) && !room.isLayoutAvailable(2) && room.isLayoutAvailable(3),"compact OOM disabled D");render(room,3,target,pose);room.dispose();
        room=new StaticRoomLayoutsMesh(source,2,true);room.setCullingEnabled(true);room.setBvhEnabled(false);room.setNearPlane(128);pose.setPosition(-77706,-1209,-103802);render(room,0,target,pose);
        for(int mode:new int[]{1,3}) {
            room.setLayout(mode);int binds=Graphics3D.binds;TriangleStripArray.failAt=TriangleStripArray.created+1;boolean failed=false;
            try{room.render(target,pose,240,320,212,212,60000);}catch(OutOfMemoryError ex){failed=true;}finally{TriangleStripArray.failAt=0;}
            ok(failed && Graphics3D.binds==binds && Graphics3D.target==null,"partial mask OOM bound incomplete layout");render(room,mode,target,pose);
        }
        for(int mode=0;mode<4;mode++)for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            room.setLayout(mode);System.setProperty(flag,"true");boolean failed=false;
            try{room.render(target,pose,240,320,212,212,60000);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"native target leaked");
        }
        for(int invalid:new int[]{-1,4}){boolean failed=false;try{room.setLayout(invalid);}catch(IllegalArgumentException ex){failed=true;}ok(failed,"invalid profile");}
        room.setLayout(3);room.discardShared();eq(room.getLayout(),0,"discard D not original");ok(room.isLayoutAvailable(2),"discard D lost C");room.setLayout(2);room.discardCompact();eq(room.getLayout(),0,"discard C not original");eq(room.availableLayouts().length(),4,"availability labels");render(room,0,target,pose);room.dispose();
    }
    static Object viewer()throws Exception {
        Class c=Class.forName("code.Rendering.StaticRoomLayoutsPreview$Viewer");Constructor ctor=c.getDeclaredConstructor(StaticRoomLayoutsPreview.class);ctor.setAccessible(true);
        Object v=ctor.newInstance(new StaticRoomLayoutsPreview());call(v,"load",new Class[0],new Object[0]);set(v,"running",true);set(v,"visible",true);return v;
    }
    static void controller()throws Exception {
        Object v=viewer();StaticRoomLayoutsMesh room=(StaticRoomLayoutsMesh)get(v,"nativeRoom");ok(!room.isBvhEnabled(),"viewer not GRID");eq(room.getNearPlane(),128,"near");eq((Integer)get(v,"far"),60000,"far");
        Graphics g=Image.createImage(240,340).getGraphics();g.screenTarget=true;g.translate(7,9);g.setClip(3,4,160,210);Font font=g.getFont();paint(v,g);ok(get(v,"error")==null,"cold viewer failed");
        int t=room.submittedTriangles,s=room.submittedCorners,arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,rgb=Graphics.drawRGBCalls;
        int x=(Integer)get(v,"x"),y=(Integer)get(v,"y"),z=(Integer)get(v,"z"),yaw=(Integer)get(v,"yaw");Object surface=get(v,"surface");
        for(int mode=1;mode<=4;mode++){key(v,48);paint(v,g);eq(room.getLayout(),mode%4,"0 cycle");ok(get(v,"error")==null,"cycle paint failed");eq(room.submittedTriangles,t,"cycle T");eq(room.submittedCorners,s,"cycle S");}
        int indices=TriangleStripArray.created;
        for(int mode=1;mode<=4;mode++){key(v,48);paint(v,g);}eq(TriangleStripArray.created,indices,"warm mode cycle uploaded indices");eq(VertexArray.created,arrays,"cycle allocated vertices");eq(VertexArray.sets,sets,"cycle uploaded vertices");eq(Graphics3D.uploads,uploads,"cycle uploaded texture");eq(Graphics.drawRGBCalls,rgb,"software fallback");
        key(v,51);paint(v,g);eq(room.getLayout(),3,"key3 shared shortcut");key(v,49);paint(v,g);eq(room.getLayout(),0,"key1 original shortcut");
        eq((Integer)get(v,"x"),x,"mode moved x");eq((Integer)get(v,"y"),y,"mode moved y");eq((Integer)get(v,"z"),z,"mode moved z");eq((Integer)get(v,"yaw"),yaw,"mode moved yaw");ok(surface==get(v,"surface"),"mode changed target");ok(g.getFont()==font && g.getTranslateX()==7 && g.getClipX()==3,"LCDUI state leaked");
        set(v,"x",x+1000);set(v,"yaw",90);key(v,53);eq((Integer)get(v,"x"),x,"home x");eq((Integer)get(v,"yaw"),0,"home yaw");
        call(v,"hideNotify",new Class[0],new Object[0]);int calls=Graphics3D.calls;paint(v,g);eq(Graphics3D.calls,calls,"hidden Canvas rendered");call(v,"showNotify",new Class[0],new Object[0]);paint(v,g);eq(((StaticRoomLayoutsStats)get(v,"stats")).frames,0,"hidden duration counted");
        key(v,48);paint(v,g);System.setProperty("m3g.oom","true");try{paint(v,g);}finally{System.clearProperty("m3g.oom");}ok(get(v,"error")!=null && !room.isLayoutAvailable(1),"B OOM not disabled");key(v,49);paint(v,g);ok(get(v,"error")==null && room.getLayout()==0,"key1 recovery");key(v,48);paint(v,g);eq(room.getLayout(),3,"did not skip unavailable B/C");
        System.setProperty("m3g.oom","true");try{paint(v,g);}finally{System.clearProperty("m3g.oom");}key(v,49);paint(v,g);eq(room.availableLayouts().indexOf('D'),-1,"failed D still available");key(v,48);paint(v,g);eq(room.getLayout(),0,"only A must stay A, not relabel unavailable mode");key(v,35);ok((Boolean)get(v,"closed"),"exit");
        Object live=viewer();StaticRoomLayoutsPreview app=new StaticRoomLayoutsPreview();set(app,"viewer",live);app.startApp();Thread worker=(Thread)get(live,"worker");app.pauseApp();ok(!(Boolean)get(live,"running"),"pause");app.startApp();ok(worker==get(live,"worker"),"duplicate worker");app.destroyApp(true);worker.join(2000);ok(!worker.isAlive(),"worker leaked");
        StaticRoomLayoutsStats stats=new StaticRoomLayoutsStats();stats.reset(0);
        for(int i=0;i<=4000;i+=100)ok(stats.record(i,i+1,i+13,i+16,i+20)==(i==4000),"warmup/anchor/3s boundary");eq(stats.fps10,100,"FPS");eq(stats.meanNative,12,"N");eq(stats.meanCopy,3,"C");eq(stats.meanInterval,100,"I");stats.record(1000,1001,1002,1003,1020);eq(stats.warmUntil,2020,"clock rollback between completed windows");
        StaticRoomLayoutsMesh fake=new StaticRoomLayoutsMesh(tiny(),2,true);fake.prepareMs=1;fake.bindMs=2;fake.clearMs=3;fake.drawMs=4;fake.releaseMs=2;stats.reset(0);
        for(int i=0;i<=4000;i+=100)stats.record(i,i+1,i+13,i+16,i+20,fake);
        eq(stats.meanJ,1,"stage J");eq(stats.meanB,2,"stage B");eq(stats.meanK,3,"stage K");eq(stats.meanR,4,"stage R");eq(stats.meanE,2,"stage E");fake.dispose();
    }
    public static void main(String[] args)throws Exception {
        Mesh source=load();sweep(source);seamsAndFailures(source);controller();
        System.out.println("PASS StaticRoomLayouts: "+checks+" checks; no rasterization or desktop phone-FPS claims.");
    }
}
