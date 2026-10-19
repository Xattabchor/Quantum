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
public final class StaticRoomPacketsTest {
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
        StaticRoomTexturesPreview.loadLightmap(meshes,StaticRoomPacketsTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],StaticRoomPacketsTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
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
            ok(y.positionCapacity<=512,"B buffer exceeds vertex cap");int childOffset=0;
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
    static void layout(StaticRoomPacketsMesh room)throws Exception {
        int vertices=0,packets=0;Vector parts=(Vector)get(room,"parts");
        for(Object part:parts) {
            ok(get(part,"packedXYZ")==null && get(part,"packedUV")==null && get(part,"packedColor")==null,"retained CPU staging arrays");
            Object[] groups=(Object[])get(part,"packets");int face=0,vertex=0;byte[] cells=(byte[])get(part,"faceCells");int[] lengths=(int[])get(part,"sourceStrips");
            for(Object group:groups) {
                int first=(Integer)get(group,"firstFace"),end=(Integer)get(group,"endFace"),count=(Integer)get(group,"count");
                eq(first,face,"packet gap/reordering");eq((Integer)get(group,"firstVertex"),vertex,"packet vertex offset");ok(count>0 && count<=512,"packet cap");
                int minX=4,minZ=4,maxX=-1,maxZ=-1,n=0;
                for(int f=first;f<end;f++){int cell=cells[f]&255;minX=Math.min(minX,cell%4);maxX=Math.max(maxX,cell%4);minZ=Math.min(minZ,cell/4);maxZ=Math.max(maxZ,cell/4);n+=lengths[f];}
                ok(maxX-minX<2 && maxZ-minZ<2,"packet not spatially bounded by centroid tiles");eq(count,n,"packet corner count");face=end;vertex+=count;vertices+=count;packets++;
            }
            eq(face,lengths.length,"cold packet faces missing");eq(vertex,(Integer)get(part,"vertexCount"),"cold packet vertices missing");
        }
        eq(vertices,10032,"scene corner count");eq(room.compactUploadedVertices,vertices,"compact uploaded count");eq(room.packetCount,packets,"packet count");eq(room.compactGeometryUploads,3*packets,"compact VA uploads");
    }
    static void sweep(Mesh source)throws Exception {
        StaticRoomBvhMesh original=new StaticRoomBvhMesh(source,2,true);original.setNearPlane(128);original.setCullingEnabled(true);
        StaticRoomPacketsMesh room=new StaticRoomPacketsMesh(source,2,true);room.setNearPlane(128);room.setCullingEnabled(true);layout(room);
        ok(room.isCompactAvailable() && !room.isCompactEnabled(),"default/cache availability");eq(room.uploadedVertices,10032,"A resident vertices");
        Texture river=source.getTexture().textures[2];RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,320).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(boolean bvh:new boolean[]{false,true})for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];original.setBvhEnabled(bvh);room.setBvhEnabled(bvh);
            Graphics3D.history.clear();original.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> expected=capture();float[] camera=Graphics3D.cameraTransform.matrix.clone();
            room.setCompactEnabled(false);Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> large=capture();sameA(expected,large);
            int triangles=room.submittedTriangles,corners=room.submittedCorners,largeVB=room.submittedBufferVertices,tests=room.visibilityTests;
            eq(triangles,original.submittedTriangles,"A selected triangles");eq(corners,original.submittedCorners,"A selected corners");eq(tests,original.visibilityTests,"selector changed");
            int sum=0;for(Graphics3D.Draw d:large)sum+=d.positionCapacity;eq(sum,largeVB,"A VB sum");
            room.setCompactEnabled(true);Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> small=capture();sameStream(large,small);
            ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"camera changed");eq(room.submittedTriangles,triangles,"B selected triangles differ");eq(room.submittedCorners,corners,"B selected corners differ");eq(room.visibilityTests,tests,"B selector differs");
            sum=0;for(Graphics3D.Draw d:small)sum+=d.positionCapacity;eq(room.submittedBufferVertices,sum,"B VB sum");ok(sum<=largeVB,"B increased submitted capacity");eq(room.draws,small.size(),"B D count");
            int indices=TriangleStripArray.created,masks=room.maskUploads,compactMasks=room.compactMaskUploads;Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);
            eq(indices,TriangleStripArray.created,"fixed mask allocated new indices");eq(masks,room.maskUploads,"A mask cache miss");eq(compactMasks,room.compactMaskUploads,"B mask cache miss");
            eq(arrays,VertexArray.created,"hot vertex allocation");eq(sets,VertexArray.sets,"hot vertex upload");eq(uploads,Graphics3D.uploads,"hot texture upload");ok(Graphics3D.target==null,"target leak");
            if(!bvh && point==points[0] && pitch==0 && yaw==0 && frame==0)System.out.println("CONTROL geometry only: A T="+triangles+" D="+large.size()+" VB="+largeVB+"; B T="+room.submittedTriangles+" D="+small.size()+" VB="+sum+"; resident A+B="+(room.uploadedVertices+room.compactUploadedVertices)+"; cold packets="+room.packetCount);
            cases++;baseCalls+=large.size();smallCalls+=small.size();
        }
        river.rImg=frames[0];room.dispose();original.dispose();System.out.println("PASS exact A/Tex3 and ordered A/B strip stream: "+cases+" cases, all4 river frames, Grid+BVH; "+water+" river strips; calls A="+baseCalls+" B="+smallCalls+" (NOT speed measurements)");
    }
    static Mesh tiny(int count) {
        Texture texture=new Texture(new RawImage(new int[]{0,-1,-1,-1},2,2),true,null);texture.drawmode=9;RenderObject[] faces=new RenderObject[count];
        for(int i=0;i<count;i++)faces[i]=new Polygon3V(new Vertex(0,0,-1200),new Vertex(1200,0,-1200),new Vertex(0,600,-1800),(byte)0,(byte)0,(byte)255,(byte)0,(byte)0,(byte)255);
        return new Mesh(new Vertex[0],faces,new MultyTexture(texture));
    }
    static void failures(Mesh source)throws Exception {
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();
        for(int kind=0;kind<2;kind++) {
            if(kind==0)VertexArray.failAt=VertexArray.created+4;else TriangleStripArray.failAt=TriangleStripArray.created+2;
            StaticRoomPacketsMesh room;try{room=new StaticRoomPacketsMesh(tiny(1),2,true);}finally{VertexArray.failAt=TriangleStripArray.failAt=0;}
            ok(!room.isCompactAvailable() && !room.isCompactEnabled(),"optional compact OOM did not preserve A");eq(room.packetCount,0,"failed packet refs retained");
            room.render(target,pose,240,320,212,212,60000);boolean failed=false;try{room.setCompactEnabled(true);}catch(IllegalStateException ex){failed=true;}ok(failed && Graphics3D.target==null,"unavailable B silently drawn as A");room.dispose();
        }
        StaticRoomPacketsMesh chunked=new StaticRoomPacketsMesh(tiny(200),2,true);eq(chunked.packetCount,2,"vertex cap split");chunked.setCompactEnabled(true);chunked.render(target,pose,240,320,212,212,60000);eq(chunked.draws,2,"split packet calls");eq(chunked.submittedTriangles,200,"split triangles");chunked.dispose();
        StaticRoomPacketsMesh room=new StaticRoomPacketsMesh(source,2,true);room.setCullingEnabled(true);room.setBvhEnabled(false);room.setNearPlane(128);pose.setPosition(-77706,-1209,-103802);
        int binds=Graphics3D.binds;TriangleStripArray.failAt=TriangleStripArray.created+1;boolean failed=false;
        try{room.render(target,pose,240,320,212,212,60000);}catch(OutOfMemoryError ex){failed=true;}finally{TriangleStripArray.failAt=0;}
        ok(failed && Graphics3D.binds==binds && Graphics3D.target==null,"selection OOM changed geometry or bound a target");ok(!room.isCullingFallbackActive(),"A/B silently changed to whole room");
        room.render(target,pose,240,320,212,212,60000);room.setCompactEnabled(true);
        binds=Graphics3D.binds;TriangleStripArray.failAt=TriangleStripArray.created+1;failed=false;
        try{room.render(target,pose,240,320,212,212,60000);}catch(OutOfMemoryError ex){failed=true;}finally{TriangleStripArray.failAt=0;}
        ok(failed && Graphics3D.binds==binds && Graphics3D.target==null,"B partial index OOM drew an incomplete frame");
        room.render(target,pose,240,320,212,212,60000);
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");failed=false;try{room.render(target,pose,240,320,212,212,60000);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"native failure target leak");
        }
        Graphics3D.failAt=Graphics3D.calls+1;failed=false;try{room.render(target,pose,240,320,212,212,60000);}catch(IllegalStateException ex){failed=true;}finally{Graphics3D.failAt=0;}ok(failed && Graphics3D.target==null,"render failure target leak");
        room.discardCompact();ok(!room.isCompactAvailable() && !room.isCompactEnabled(),"B OOM discard");room.render(target,pose,240,320,212,212,60000);room.dispose();failed=false;try{room.render(target,pose,240,320,212,212,60000);}catch(IllegalStateException ex){failed=true;}ok(failed,"disposed room rendered");
    }
    static Object viewer()throws Exception {
        ((Hashtable)getStatic(Asset.class,"table")).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class c=Class.forName("code.Rendering.StaticRoomPacketsPreview$Viewer");Constructor ctor=c.getDeclaredConstructor(StaticRoomPacketsPreview.class);ctor.setAccessible(true);
        Object v=ctor.newInstance(new StaticRoomPacketsPreview());call(v,"load",new Class[0],new Object[0]);set(v,"running",true);set(v,"visible",true);return v;
    }
    static Object getStatic(Class type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static void viewerChecks()throws Exception {
        Object v=viewer();StaticRoomPacketsMesh room=(StaticRoomPacketsMesh)get(v,"nativeRoom");eq(Image.resourceReads.size(),6,"cold texture resource reads");ok(Image.missingResources.isEmpty(),"missing PNG/river");
        ok(!room.isCompactEnabled() && !room.isBvhEnabled(),"default not A/Grid");eq(room.getNearPlane(),128,"near changed");eq((Integer)get(v,"far"),60000,"far changed");
        Graphics g=Image.createImage(240,340).getGraphics();g.screenTarget=true;g.translate(7,9);g.setClip(3,4,160,210);Font font=g.getFont();
        paint(v,g);ok(get(v,"error")==null,"cold viewer failed");int t=room.submittedTriangles,s=room.submittedCorners;int x=(Integer)get(v,"x"),y=(Integer)get(v,"y"),z=(Integer)get(v,"z"),yaw=(Integer)get(v,"yaw");
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,rgb=Graphics.drawRGBCalls;Object surface=get(v,"surface");
        key(v,48);paint(v,g);ok(room.isCompactEnabled() && get(v,"error")==null,"0 did not select B");eq(room.submittedTriangles,t,"toggle changed visible triangles");eq(room.submittedCorners,s,"toggle changed visible corners");
        eq((Integer)get(v,"x"),x,"toggle moved x");eq((Integer)get(v,"y"),y,"toggle moved y");eq((Integer)get(v,"z"),z,"toggle moved z");eq((Integer)get(v,"yaw"),yaw,"toggle rotated camera");ok(get(v,"surface")==surface,"toggle replaced target");
        int indices=TriangleStripArray.created;key(v,48);paint(v,g);key(v,48);paint(v,g);eq(TriangleStripArray.created,indices,"warm A/B rebuilt indices");eq(VertexArray.created,arrays,"toggle rebuilt geometry");eq(VertexArray.sets,sets,"toggle uploaded geometry");eq(Graphics3D.uploads,uploads,"toggle uploaded texture");eq(Graphics.drawRGBCalls,rgb,"software fallback");
        ok(g.getFont()==font && g.getTranslateX()==7 && g.getTranslateY()==9 && g.getClipX()==3 && g.getClipY()==4 && g.getClipWidth()==160,"LCDUI state not restored");
        key(v,54);call(v,"keyReleased",new Class[]{int.class},new Object[]{54});eq((Integer)get(v,"turn"),0,"turn key release");
        set(v,"x",x+1000);set(v,"z",z-1000);set(v,"yaw",90);key(v,53);eq((Integer)get(v,"x"),x,"5 home x");eq((Integer)get(v,"z"),z,"5 home z");eq((Integer)get(v,"yaw"),0,"5 home yaw");
        call(v,"hideNotify",new Class[0],new Object[0]);int calls=Graphics3D.calls;paint(v,g);eq(Graphics3D.calls,calls,"hidden Canvas rendered");call(v,"showNotify",new Class[0],new Object[0]);paint(v,g);eq(((StaticRoomPacketsStats)get(v,"stats")).frames,0,"shown Canvas counted background time");
        // Warm B native failure, then explicit original-A recovery at same pose.
        Graphics3D.failAt=Graphics3D.calls+1;paint(v,g);Graphics3D.failAt=0;ok(get(v,"error")!=null && Graphics3D.target==null,"viewer native failure");key(v,48);paint(v,g);ok(get(v,"error")==null && !room.isCompactEnabled(),"A recovery failed");
        key(v,48);System.setProperty("m3g.oom","true");try{paint(v,g);}finally{System.clearProperty("m3g.oom");}ok(get(v,"error")!=null && !room.isCompactAvailable(),"viewer did not discard failed B");key(v,48);paint(v,g);ok(get(v,"error")==null && !room.isCompactEnabled(),"OOM A recovery");key(v,48);paint(v,g);ok(!room.isCompactEnabled() && ((String)get(v,"heading")).contains("unavailable"),"unavailable B falsely labelled");
        key(v,35);ok((Boolean)get(v,"closed"),"# close");
        Object live=viewer();StaticRoomPacketsPreview app=new StaticRoomPacketsPreview();set(app,"viewer",live);app.startApp();Thread worker=(Thread)get(live,"worker");
        app.pauseApp();ok(!(Boolean)get(live,"running"),"MIDlet pause");app.startApp();ok(get(live,"worker")==worker,"resume created duplicate workers");app.destroyApp(true);worker.join(2000);ok(!worker.isAlive(),"worker leaked after destroy");
        StaticRoomPacketsStats stats=new StaticRoomPacketsStats();stats.reset(0);for(int i=0;i<1000;i+=100)ok(!stats.record(i,i+1,i+13,i+16,i+20),"warm counted");ok(!stats.record(1000,1001,1013,1016,1020),"anchor counted");
        for(int i=1100;i<=4000;i+=100){boolean done=stats.record(i,i+1,i+13,i+16,i+20);ok(done==(i==4000),"3s timing boundary");}
        eq(stats.fps10,100,"fractional FPS");eq(stats.meanNative,12,"N mean");eq(stats.meanCopy,3,"C mean");eq(stats.meanInterval,100,"I mean");
        stats.reset(100000);stats.record(100010,100011,100012,100013,100020);stats.record(1000,1001,1002,1003,1020);eq(stats.warmUntil,2020,"clock rollback warm reset");
    }
    public static void main(String[] args)throws Exception {
        Mesh source=load();sweep(source);failures(source);viewerChecks();
        System.out.println("PASS StaticRoomPackets: "+checks+" checks; exact same selected geometry/state, no hot VA/texture uploads. No desktop FPS claims.");
    }
}
