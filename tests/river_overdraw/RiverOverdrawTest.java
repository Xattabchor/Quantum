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

/** Exact geometry/order contracts for overdraw modes; NOT a rasterizer or FPS test. */
public final class RiverOverdrawTest {
    static long checks;static int cases,water;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message+" ("+x+" != "+y+")");}
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
        StaticRoomTexturesPreview.loadLightmap(meshes,RiverOverdrawTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],RiverOverdrawTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.colorWrite?1:0,d.alphaWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static boolean sameGeometry(Graphics3D.Draw x,Graphics3D.Draw y) {
        return Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors)
            && Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths)
            && x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias)
            && Arrays.equals(x.projection,y.projection) && x.positionCapacity==y.positionCapacity;
    }
    static boolean sameDraw(Graphics3D.Draw x,Graphics3D.Draw y) {
        return sameGeometry(x,y) && Arrays.equals(state(x),state(y)) && Arrays.equals(x.source.image.pixels,y.source.image.pixels);
    }
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"A changed call count");
        for(int i=0;i<a.size();i++)ok(sameDraw(a.get(i),b.get(i)),"A draw stream differs from immutable Tex3 at "+i);
    }
    static void sameStream(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        int parent=0,face=0,offset=0;
        for(Graphics3D.Draw y:b) {
            ok(y.positionCapacity<=512,"compact buffer exceeds vertex cap");int childOffset=0;
            for(int length:y.lengths) {
                while(parent<a.size() && face==a.get(parent).lengths.length){parent++;face=offset=0;}
                ok(parent<a.size(),"compact submitted extra faces");Graphics3D.Draw x=a.get(parent);eq(length,x.lengths[face],"face/strip order changed");
                ok(x.source==y.source && Arrays.equals(state(x),state(y)) && Arrays.equals(x.projection,y.projection),"compact material/state/projection/order changed");
                ok(x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias),"compact requantized positions/UV");
                for(int j=0;j<length;j++) {
                    int ix=x.indices[offset+j],iy=y.indices[childOffset+j];ok(iy>=0 && iy<y.positionCapacity,"compact local index invalid");
                    for(int k=0;k<3;k++)eq(x.positions[ix*3+k],y.positions[iy*3+k],"compact XYZ changed");
                    for(int k=0;k<2;k++)eq(x.coords[ix*2+k],y.coords[iy*2+k],"compact UV changed");
                    for(int k=0;k<4;k++)eq(x.colors[ix*4+k],y.colors[iy*4+k],"compact baked RGBA changed");
                }
                if(y.blend==CompositingMode.MODULATE)water++;
                offset+=length;childOffset+=length;face++;
            }
            eq(childOffset,y.indices.length,"unconsumed compact indices");
        }
        while(parent<a.size() && face==a.get(parent).lengths.length){parent++;face=offset=0;}
        eq(parent,a.size(),"compact lost source faces");
    }
    static List<Graphics3D.Draw> opaque(List<Graphics3D.Draw> draws) {
        List<Graphics3D.Draw> out=new ArrayList<Graphics3D.Draw>();
        for(Graphics3D.Draw d:draws)if(d.blend!=CompositingMode.MODULATE)out.add(d);
        return out;
    }
    static List<Graphics3D.Draw> river(List<Graphics3D.Draw> draws) {
        List<Graphics3D.Draw> out=new ArrayList<Graphics3D.Draw>();
        for(Graphics3D.Draw d:draws)if(d.blend==CompositingMode.MODULATE)out.add(d);
        return out;
    }
    static void sameMultiset(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b,String label) {
        eq(a.size(),b.size(),label+" call count");
        boolean[] used=new boolean[b.size()];
        for(Graphics3D.Draw x:a) {
            int found=-1;
            for(int i=0;i<b.size();i++)if(!used[i] && sameDraw(x,b.get(i))){found=i;break;}
            ok(found>=0,label+" draw missing or changed");used[found]=true;
        }
    }
    static float depthKey(Graphics3D.Draw d,Matrix pose) {
        float minX=Float.MAX_VALUE,minY=Float.MAX_VALUE,minZ=Float.MAX_VALUE,maxX=-Float.MAX_VALUE,maxY=-Float.MAX_VALUE,maxZ=-Float.MAX_VALUE;
        for(int i=0;i<d.positionCapacity;i++) {
            float x=d.positions[i*3]*d.scale+d.bias[0],y=d.positions[i*3+1]*d.scale+d.bias[1],z=d.positions[i*3+2]*d.scale+d.bias[2];
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        float fx=-(pose.m02/(float)Matrix.FP),fy=-(pose.m12/(float)Matrix.FP),fz=-(pose.m22/(float)Matrix.FP);
        float cx=(minX+maxX)*0.5f,cy=(minY+maxY)*0.5f,cz=(minZ+maxZ)*0.5f;
        float hx=(maxX-minX)*0.5f,hy=(maxY-minY)*0.5f,hz=(maxZ-minZ)*0.5f;
        return (cx-pose.m03)*fx+(cy-pose.m13)*fy+(cz-pose.m23)*fz-(Math.abs(fx)*hx+Math.abs(fy)*hy+Math.abs(fz)*hz);
    }
    static void checkSorted(List<Graphics3D.Draw> draws,Matrix pose) {
        boolean seenRiver=false;float previous=-Float.MAX_VALUE;
        for(Graphics3D.Draw d:draws) {
            if(d.blend==CompositingMode.MODULATE){seenRiver=true;continue;}
            ok(!seenRiver,"opaque packet drawn after river");
            float key=depthKey(d,pose);ok(key>=previous-0.01f,"front-to-back order violated: "+previous+" -> "+key);previous=key;
        }
    }
    static List<Graphics3D.Draw> drawMode(RiverOverdrawMesh room,Graphics target,Matrix pose,int mode) {
        room.setCompactEnabled(mode!=0);room.setFrontToBackEnabled(mode>=2);room.setDepthPrepassEnabled(mode==3);
        Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);ok(Graphics3D.target==null,"target leak");return capture();
    }
    static int vb(List<Graphics3D.Draw> draws){int sum=0;for(Graphics3D.Draw d:draws)sum+=d.positionCapacity;return sum;}
    static void checkPrepass(RiverOverdrawMesh room,Graphics target,Matrix pose) {
        List<Graphics3D.Draw> f2b=drawMode(room,target,pose,2);
        int t=room.submittedTriangles,s=room.submittedCorners,sorted=room.sortedPackets;
        List<Graphics3D.Draw> op=opaque(f2b),rv=river(f2b);eq(sorted,op.size(),"sorted opaque packet count");
        List<Graphics3D.Draw> z=drawMode(room,target,pose,3);
        eq(room.submittedTriangles,t,"ZPRE logical triangles");eq(room.submittedCorners,s,"ZPRE logical corners");eq(room.sortedPackets,sorted,"ZPRE sorted packets");
        eq(z.size(),op.size()*2+rv.size(),"ZPRE call count");eq(room.draws,z.size(),"ZPRE DC counter");eq(room.submittedBufferVertices,vb(z),"ZPRE VB counter");
        for(int i=0;i<op.size();i++) {
            Graphics3D.Draw depth=z.get(i),color=z.get(op.size()+i),expected=op.get(i);
            ok(sameGeometry(expected,depth),"depth pass geometry changed at "+i);
            ok(!depth.colorWrite && !depth.alphaWrite && depth.depthTest && depth.depthWrite,"depth pass write masks at "+i);
            ok(depth.threshold==expected.threshold,"depth pass alpha threshold at "+i);
            if(expected.threshold>0){ok(depth.textured && Arrays.equals(depth.source.image.pixels,expected.source.image.pixels),"cutout depth pass texture at "+i);}
            else ok(!depth.textured,"opaque depth pass should not sample texture at "+i);
            ok(sameGeometry(expected,color),"color pass geometry changed at "+i);
            ok(color.colorWrite && color.depthTest && !color.depthWrite,"color pass write masks at "+i);
            ok(color.textured && Arrays.equals(color.source.image.pixels,expected.source.image.pixels) && color.threshold==expected.threshold && color.blend==expected.blend,"color pass state at "+i);
        }
        for(int i=0;i<rv.size();i++)ok(sameDraw(rv.get(i),z.get(op.size()*2+i)),"river pass changed at "+i);
    }

    static void sweep(Mesh source)throws Exception {
        StaticRoomBvhMesh original=new StaticRoomBvhMesh(source,2,true);original.setNearPlane(128);original.setCullingEnabled(true);original.setBvhEnabled(false);
        RiverOverdrawMesh room=new RiverOverdrawMesh(source,2,true);room.setNearPlane(128);room.setCullingEnabled(true);room.setBvhEnabled(false);
        ok(room.isCompactAvailable(),"compact packets unavailable");eq(room.triangles,4750,"full triangles");eq(room.uploadedVertices,10032,"resident vertices");eq(room.parts(),3,"parts");
        Texture river=source.getTexture().textures[2];RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,320).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=60)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];
            Graphics3D.history.clear();original.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> expected=capture();
            List<Graphics3D.Draw> large=drawMode(room,target,pose,0);sameA(expected,large);
            int triangles=room.submittedTriangles,corners=room.submittedCorners,tests=room.visibilityTests;
            eq(triangles,original.submittedTriangles,"A triangles");eq(corners,original.submittedCorners,"A corners");eq(tests,original.visibilityTests,"selector changed");
            List<Graphics3D.Draw> compact=drawMode(room,target,pose,1);sameStream(large,compact);
            eq(room.submittedTriangles,triangles,"C ORIG triangles");eq(room.submittedCorners,corners,"C ORIG corners");eq(room.visibilityTests,tests,"C ORIG selector");
            eq(room.submittedBufferVertices,vb(compact),"C ORIG VB");eq(room.draws,compact.size(),"C ORIG DC");
            List<Graphics3D.Draw> sorted=drawMode(room,target,pose,2);sameMultiset(compact,sorted,"C F2B");
            eq(room.submittedTriangles,triangles,"C F2B triangles");eq(room.submittedCorners,corners,"C F2B corners");eq(room.submittedBufferVertices,vb(sorted),"C F2B VB");eq(room.draws,sorted.size(),"C F2B DC");
            eq(room.sortedPackets,opaque(sorted).size(),"sorted packet counter");checkSorted(sorted,pose);
            int indices=TriangleStripArray.created,masks=room.maskUploads,compactMasks=room.compactMaskUploads;
            drawMode(room,target,pose,2);
            eq(indices,TriangleStripArray.created,"stationary F2B allocated indices");eq(masks,room.maskUploads,"stationary mask rebuilt");eq(compactMasks,room.compactMaskUploads,"stationary compact mask rebuilt");
            eq(arrays,VertexArray.created,"hot vertex allocation");eq(sets,VertexArray.sets,"hot vertex upload");eq(uploads,Graphics3D.uploads,"hot texture upload");
            cases++;
        }
        pose.setRotY(0);pose.setPosition(points[0][0],points[0][1],points[0][2]);river.rImg=frames[0];checkPrepass(room,target,pose);
        pose.setRotX(30);pose.rotY(120);pose.setPosition(points[1][0],points[1][1],points[1][2]);river.rImg=frames[3];checkPrepass(room,target,pose);
        river.rImg=frames[0];room.dispose();original.dispose();
    }

    static void failures(Mesh source)throws Exception {
        RiverOverdrawMesh room=new RiverOverdrawMesh(source,2,true);room.setCullingEnabled(true);room.setBvhEnabled(false);room.setNearPlane(128);
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();pose.setPosition(-77706,-1209,-103802);
        drawMode(room,target,pose,2);
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;
            try{drawMode(room,target,pose,2);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"native failure target leak "+flag);
        }
        System.setProperty("m3g.oom","true");boolean failed=false;
        try{drawMode(room,target,pose,3);}catch(Throwable ex){failed=true;}finally{System.clearProperty("m3g.oom");}
        ok(failed && Graphics3D.target==null,"ZPRE native failure target leak");
        drawMode(room,target,pose,2);room.dispose();failed=false;
        try{room.render(target,pose,240,320,212,212,60000);}catch(IllegalStateException ex){failed=true;}
        ok(failed,"disposed room rendered");
    }

    static Object getStatic(Class type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static Object viewer()throws Exception {
        ((Hashtable)getStatic(Asset.class,"table")).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class c=Class.forName("code.Rendering.RiverOverdrawPreview$Viewer");Constructor ctor=c.getDeclaredConstructor(RiverOverdrawPreview.class);ctor.setAccessible(true);
        Object v=ctor.newInstance(new RiverOverdrawPreview());call(v,"load",new Class[0],new Object[0]);set(v,"running",Boolean.TRUE);set(v,"visible",Boolean.TRUE);return v;
    }
    static void resetScreen(Graphics screen){screen.stringCalls=0;screen.rectCalls=0;screen.lastString=null;Graphics3D.history.clear();}
    static boolean bool(Object v,String name)throws Exception{return ((Boolean)get(v,name)).booleanValue();}
    static int integer(Object v,String name)throws Exception{return ((Integer)get(v,name)).intValue();}
    static void viewerChecks()throws Exception {
        Object v=viewer();RiverOverdrawMesh room=(RiverOverdrawMesh)get(v,"nativeRoom");
        eq(Image.resourceReads.size(),6,"cold texture reads");ok(Image.missingResources.isEmpty(),"missing textures");
        eq(integer(v,"mode"),2,"default not C F2B");eq(room.getNearPlane(),128,"near");eq(integer(v,"far"),60000,"far");ok(!room.isBvhEnabled(),"BVH not OFF");
        Graphics screen=Image.createImage(240,340).getGraphics();resetScreen(screen);paint(v,screen);ok(get(v,"error")==null,"cold paint failed");
        eq(screen.stringCalls,2,"live HUD strings");eq(screen.rectCalls,1,"live HUD panel");ok(((String)get(v,"heading")).startsWith("C F2B"),"default heading");
        ok(room.isCompactEnabled() && room.isFrontToBackEnabled() && !room.isDepthPrepassEnabled(),"default room mode");
        int x=integer(v,"x"),y=integer(v,"y"),z=integer(v,"z"),yaw=integer(v,"yaw");Object surface=get(v,"surface");
        int triangles=room.submittedTriangles,corners=room.submittedCorners;
        key(v,49);resetScreen(screen);paint(v,screen);eq(integer(v,"mode"),0,"key1 mode");ok(!room.isCompactEnabled(),"A not large");eq(room.submittedTriangles,triangles,"A triangles changed");
        key(v,51);resetScreen(screen);paint(v,screen);eq(integer(v,"mode"),1,"key3 mode");ok(room.isCompactEnabled() && !room.isFrontToBackEnabled(),"C ORIG room mode");eq(room.submittedTriangles,triangles,"C ORIG triangles changed");
        key(v,55);resetScreen(screen);paint(v,screen);eq(integer(v,"mode"),2,"key7 mode");ok(room.isFrontToBackEnabled() && !room.isDepthPrepassEnabled(),"C F2B room mode");eq(room.submittedTriangles,triangles,"C F2B triangles changed");
        key(v,57);resetScreen(screen);paint(v,screen);eq(integer(v,"mode"),3,"key9 mode");ok(room.isDepthPrepassEnabled(),"C ZPRE room mode");eq(room.submittedTriangles,triangles,"C ZPRE triangles changed");eq(room.submittedCorners,corners,"mode triangles/corners changed");
        key(v,48);eq(integer(v,"mode"),0,"key0 cycle to A");paint(v,screen);
        eq(integer(v,"x"),x,"mode moved x");eq(integer(v,"y"),y,"mode moved y");eq(integer(v,"z"),z,"mode moved z");eq(integer(v,"yaw"),yaw,"mode moved yaw");ok(surface==get(v,"surface"),"mode rebuilt surface");
        key(v,55);paint(v,screen);
        set(v,"heading","C F2B FPS 9.9");set(v,"fullTimings","N 1 C 2 I 3 ms");set(v,"counts","T 1 DC 1 VB 1");set(v,"extra","S 1 V 1 CUT 1 P 1");set(v,"stages","J/B/K/R/E 1/1/1/1/1");set(v,"hasResult",Boolean.TRUE);
        key(v,42);ok(bool(v,"showResult"),"star did not freeze");key(v,52);eq(integer(v,"turn"),0,"movement while frozen");
        resetScreen(screen);paint(v,screen);eq(Graphics3D.history.size(),0,"frozen rendered native");eq(screen.stringCalls,6,"frozen strings");eq(screen.rectCalls,1,"frozen panel");ok("* resume;0 mode;5 home".equals(screen.lastString),"frozen help");
        key(v,42);ok(!bool(v,"showResult") && bool(v,"resetRequested"),"star did not resume/reset");
        set(v,"x",Integer.valueOf(x+1000));set(v,"z",Integer.valueOf(z-1000));set(v,"yaw",Integer.valueOf(90));key(v,53);
        eq(integer(v,"x"),x,"home x");eq(integer(v,"z"),z,"home z");eq(integer(v,"yaw"),0,"home yaw");
        call(v,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(v,screen);eq(Graphics3D.history.size(),0,"hidden render");
        call(v,"showNotify",new Class[0],new Object[0]);ok(bool(v,"resetRequested"),"show reset");
        RiverOverdrawStats stats=new RiverOverdrawStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522,room),"warm published");ok(!stats.record(1000,1000,1020,1021,1022,room),"anchor published");
        room.prepareMs=2;room.bindMs=3;room.clearMs=4;room.drawMs=5;room.releaseMs=6;
        ok(!stats.record(2000,2000,2020,2021,2022,room),"short window published");ok(stats.record(4000,4000,4020,4021,4022,room),"window missing");
        eq(stats.fps10,6,"stats FPS");eq(stats.meanNative,20,"stats N");eq(stats.meanCopy,1,"stats C");eq(stats.meanInterval,1500,"stats I");
        eq(stats.meanJ,2,"stats J");eq(stats.meanB,3,"stats B");eq(stats.meanK,4,"stats K");eq(stats.meanR,5,"stats R");eq(stats.meanE,6,"stats E");
        call(v,"release",new Class[0],new Object[0]);
    }

    public static void main(String[] args)throws Exception {
        Mesh source=load();sweep(source);failures(source);viewerChecks();
        System.out.println("PASS RiverOverdraw: "+checks+" checks / "+cases+" poses/river frames; exact A/C geometry, sorted packets and ZPRE contracts. NOT device FPS or rasterization.");
    }
}
