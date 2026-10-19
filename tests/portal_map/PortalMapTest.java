package code.Rendering;

import code.Gameplay.Map.*;
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
public final class PortalMapTest {
    static long checks;static int cases,water,baseCalls,smallCalls;
    static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
    static void eq(long x,long y,String message){ok(x==y,message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class[] types,Object[] args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    static void key(Object v,int key)throws Exception{call(v,"keyPressed",new Class[]{int.class},new Object[]{Integer.valueOf(key)});}
    static void paint(Object v,Graphics g)throws Exception{call(v,"paint",new Class[]{Graphics.class},new Object[]{g});}
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
    static Mesh[] load()throws Exception{Main.settings=GameIni.createGameIniFromResource("/setting.txt",true);Main.pixelsQ=2;Main.persQ=4;Main.m3gPerspective=true;return PortalMapData.load(PortalMapData.config());}
    static List<Graphics3D.Draw> draw(PortalMapMesh mesh,boolean enabled,Graphics target,Matrix pose,int w,int h,int f){return draw(mesh,enabled,target,pose,w,h,f,f);}
    static List<Graphics3D.Draw> draw(PortalMapMesh mesh,boolean enabled,Graphics target,Matrix pose,int w,int h,int fx,int fy){mesh.setPortalsEnabled(enabled);Graphics3D.history.clear();int binds=Graphics3D.binds,clears=Graphics3D.clears,releases=Graphics3D.releases;mesh.render(target,pose,w,h,fx,fy,40000);eq(Graphics3D.binds,binds+1,"must bind once, not once per room");eq(Graphics3D.clears,clears+1,"must clear once, not between rooms");eq(Graphics3D.releases,releases+1,"must release once");ok(Graphics3D.target==null&&Graphics3D.boundDepth,"depth/target");return capture();}
    static int corners(Mesh m){int n=0;for(RenderObject p:m.getPolygons())n+=p instanceof Polygon4V?4:3;return n;}
    static int triangles(Mesh m){return corners(m)-2*m.getPolygons().length;}
    static void pathOracle(int room,int path,int x1,int y1,int x2,int y2,PortalMapVisibility v,boolean[] valid,int[] rect,int[] result){
        result[0]|=1<<room;
        for(int e=v.offsets[room];e<v.offsets[room+1];e++){
            int p=v.edges[e],other=v.links[p*2]==room?v.links[p*2+1]:v.links[p*2];if((path&(1<<other))!=0 || !valid[p])continue;
            int b=p*4,a=Math.max(x1,rect[b]),c=Math.max(y1,rect[b+1]),d=Math.min(x2,rect[b+2]),f=Math.min(y2,rect[b+3]);
            if(a<d && c<f)pathOracle(other,path|(1<<other),a,c,d,f,v,valid,rect,result);
        }
    }
    static double hit(double[] origin,double[] d,double[] v,int a,int b,int c){
        double e0=v[b]-v[a],e1=v[b+1]-v[a+1],e2=v[b+2]-v[a+2],f0=v[c]-v[a],f1=v[c+1]-v[a+1],f2=v[c+2]-v[a+2];
        double h0=d[1]*f2-d[2]*f1,h1=d[2]*f0-d[0]*f2,h2=d[0]*f1-d[1]*f0,det=e0*h0+e1*h1+e2*h2;if(Math.abs(det)<1e-10)return -1;
        double s0=origin[0]-v[a],s1=origin[1]-v[a+1],s2=origin[2]-v[a+2],u=(s0*h0+s1*h1+s2*h2)/det;if(u<0||u>1)return -1;
        double q0=s1*e2-s2*e1,q1=s2*e0-s0*e2,q2=s0*e1-s1*e0,w=(d[0]*q0+d[1]*q1+d[2]*q2)/det;if(w<0||u+w>1)return -1;
        double t=(f0*q0+f1*q1+f2*q2)/det;return t>=.015625?t:-1;
    }
    static void rayOracle(PortalMapVisibility v,int mask,float[] m,int width,int height,int fx,int fy)throws Exception {
        boolean[] valid=(boolean[])get(v,"valid");int[] rect=(int[])get(v,"projected");double[] origin={m[3],m[7],m[11]};
        for(int px:new int[]{0,width/2,width-1})for(int py:new int[]{0,height/2,height-1}){
            double cx=(px+.5-width*.5)/fx,cy=-(py+.5-height*.5)/fy;double[] d={m[0]*cx+m[1]*cy-m[2],m[4]*cx+m[5]*cy-m[6],m[8]*cx+m[9]*cy-m[10]};double[] depths=new double[v.portalCount];
            for(int p=0;p<v.portalCount;p++){
                double[] xyz=v.vertices[p];double nearest=Double.POSITIVE_INFINITY;
                for(int i=1;i<xyz.length/3-1;i++){double t=hit(origin,d,xyz,0,i*3,(i+1)*3);if(t>=0)nearest=Math.min(nearest,t);}
                depths[p]=nearest;
                if(nearest!=Double.POSITIVE_INFINITY){int b=p*4;ok(valid[p],"ray-visible portal rejected");ok(px+.5>=rect[b]&&px+.5<=rect[b+2]&&py+.5>=rect[b+1]&&py+.5<=rect[b+3],"portal rect lost ray pixel");}
            }
            int room=v.currentRoom;double depth=0;
            for(int step=0;step<v.portalCount;step++){
                ok((mask&(1<<room))!=0,"portal mask lost independently traced ray room");double nearest=Double.POSITIVE_INFINITY;int portal=-1;
                for(int e=v.offsets[room];e<v.offsets[room+1];e++){int p=v.edges[e];if(depths[p]>depth+1e-5 && depths[p]<nearest){nearest=depths[p];portal=p;}}
                if(portal<0)break;room=v.links[portal*2]==room?v.links[portal*2+1]:v.links[portal*2];depth=nearest;
            }
        }
    }
    static void selectorOracle(PortalMapMesh mesh,int width,int height)throws Exception{selectorOracle(mesh,width,height,212,212);}
    static void selectorOracle(PortalMapMesh mesh,int width,int height,int fx,int fy)throws Exception {
        PortalMapVisibility v=(PortalMapVisibility)get(mesh,"selector");
        if(v.currentRoom<0){eq(mesh.roomMask,(1<<15)-1,"outside not fail-open");return;}
        int[] reference={0};pathOracle(v.currentRoom,1<<v.currentRoom,0,0,width,height,v,(boolean[])get(v,"valid"),(int[])get(v,"projected"),reference);
        eq(mesh.roomMask & reference[0],reference[0],"union traversal lost a simple portal path");rayOracle(v,mesh.roomMask,Graphics3D.cameraTransform.matrix,width,height,fx,fy);ok((mesh.roomMask&(1<<v.currentRoom))!=0,"seed room missing");
    }
    static void focusedSelectorTests(Mesh[] source)throws Exception {
        PortalMapVisibility v=new PortalMapVisibility(source);boolean[] valid=(boolean[])get(v,"valid");int[] rect=(int[])get(v,"projected");
        // Exact eye-on-doorway and doorway in front of, but closer than, near128.
        for(int p=0;p<v.portalCount;p++){
            double[] xyz=v.vertices[p],center=new double[3];for(int i=0;i<xyz.length;i++)center[i%3]+=xyz[i]/(xyz.length/3);
            float[] m={1,0,0,(float)center[0],0,1,0,(float)center[1],0,0,1,(float)center[2],0,0,0,1};
            call(v,"invert",new Class[]{float[].class},new Object[]{m});call(v,"project",new Class[]{int.class,float[].class,int.class,int.class,int.class,int.class},new Object[]{p,m,240,320,212,212});
            ok(valid[p]&&rect[p*4]==0&&rect[p*4+1]==0&&rect[p*4+2]==240&&rect[p*4+3]==320,"eye on doorway did not join full view");
            double[] n={ (xyz[4]-xyz[1])*(xyz[8]-xyz[2])-(xyz[5]-xyz[2])*(xyz[7]-xyz[1]),(xyz[5]-xyz[2])*(xyz[6]-xyz[0])-(xyz[3]-xyz[0])*(xyz[8]-xyz[2]),(xyz[3]-xyz[0])*(xyz[7]-xyz[1])-(xyz[4]-xyz[1])*(xyz[6]-xyz[0])};double length=Math.sqrt(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]);for(int k=0;k<3;k++)n[k]/=length;
            double[] rx={n[2],0,-n[0]};double rl=Math.sqrt(rx[0]*rx[0]+rx[2]*rx[2]);if(rl<1e-5){rx=new double[]{1,0,0};rl=1;}for(int k=0;k<3;k++)rx[k]/=rl;double[] ry={n[1]*rx[2]-n[2]*rx[1],n[2]*rx[0]-n[0]*rx[2],n[0]*rx[1]-n[1]*rx[0]};
            for(int r=0;r<3;r++){m[r*4]=(float)rx[r];m[r*4+1]=(float)ry[r];m[r*4+2]=(float)n[r];m[r*4+3]=(float)(center[r]+16*n[r]);}
            call(v,"invert",new Class[]{float[].class},new Object[]{m});call(v,"project",new Class[]{int.class,float[].class,int.class,int.class,int.class,int.class},new Object[]{p,m,240,320,212,212});ok(valid[p]&&rect[p*4]<=120&&rect[p*4+2]>=120&&rect[p*4+1]<=160&&rect[p*4+3]>=160,"door before near128 incorrectly rejected");
            for(int r=0;r<3;r++)m[r*4+3]=(float)(center[r]-16*n[r]);call(v,"invert",new Class[]{float[].class},new Object[]{m});call(v,"project",new Class[]{int.class,float[].class,int.class,int.class,int.class,int.class},new Object[]{p,m,240,320,212,212});ok(!valid[p],"door entirely behind camera kept");
        }
        // Overlap is NOT containment: room1 must requeue when the later path
        // through room3 widens its left window to include right-hand doorway2.
        Arrays.fill(valid,false);for(int p=0;p<4;p++)valid[p]=true;
        int[] links={0,1,0,3,3,1,1,2},offsets={0,2,5,6,8},edges={0,1,0,2,3,3,1,2},boxes={0,0,60,100,40,0,100,100,40,0,100,100,80,0,90,100};
        System.arraycopy(links,0,v.links,0,8);System.arraycopy(offsets,0,v.offsets,0,5);for(int i=5;i<v.offsets.length;i++)v.offsets[i]=8;System.arraycopy(edges,0,v.edges,0,8);System.arraycopy(boxes,0,rect,0,16);
        v.portalTests=0;int mask=(Integer)call(v,"traverse",new Class[]{int.class,int.class,int.class},new Object[]{0,100,100});eq(mask,15,"later overlapping path not propagated");ok(v.portalTests<100,"cyclic graph failed to converge");v.dispose();
    }
    static void graphMatchesEngine(Mesh[] source,PortalMapVisibility actual)throws Exception {
        byte old=DirectX7.standartDrawmode;House house;
        // Build the real unchanged HouseCreator graph, but DO NOT launch or reload
        // LightMapper: lighting has already been decoded strictly from the VLA.
        try{DirectX7.standartDrawmode=0;house=HouseCreator.create(source,false,null,false);}finally{DirectX7.standartDrawmode=old;}
        int directed=0;
        for(Room room:house.getRooms())for(Portal p:room.getPortals()){
            ok(p.getRoom()!=null,"original engine found unpaired portal");boolean found=false;
            for(int i=0;i<actual.portalCount;i++)if((actual.links[i*2]==room.getId()&&actual.links[i*2+1]==p.getRoom().getId())||(actual.links[i*2+1]==room.getId()&&actual.links[i*2]==p.getRoom().getId()))found=true;
            ok(found,"graph differs from engine linking");directed++;
        }
        eq(directed,36,"directed portals");
    }
    static void lightmapChecks(Mesh[] meshes)throws Exception {
        // Independent reference: decode one entire polygon's bytes into expected
        // native RGBA ordering and compare every R/G/B channel, including gray.
        java.io.DataInputStream in=new java.io.DataInputStream(PortalMapTest.class.getResourceAsStream("/portal-map/lightmap/kordon.vla"));int colored=0,gray=0;
        for(Mesh m:meshes)for(RenderObject p:m.getPolygons()){
            int n=p instanceof Polygon4V?4:3;int[] r=new int[n];for(int i=0;i<n;i++)r[i]=in.readByte()+128;boolean rgb=in.readBoolean();if(rgb)colored++;else gray++;
            for(int i=0;i<n;i++){
                int g=rgb?in.readByte()+128:r[i],b=rgb?in.readByte()+128:r[i];
                String prefix=i==0?"a":i==1?"b":i==2?"c":"d";eq((Integer)get(p,prefix+"r"),r[i],"light R");eq((Integer)get(p,prefix+"g"),g,"light G");eq((Integer)get(p,prefix+"b"),b,"light B");
            }
        }
        eq(in.read(),-1,"light EOF");in.close();eq(colored,2085,"RGB faces");eq(gray,30,"gray faces");
        byte[] good=new byte[25956];in=new java.io.DataInputStream(PortalMapTest.class.getResourceAsStream("/portal-map/lightmap/kordon.vla"));in.readFully(good);in.close();
        for(byte[] bad:new byte[][]{new byte[0],Arrays.copyOf(good,good.length-1),Arrays.copyOf(good,good.length+1)}){boolean failed=false;try{PortalMapData.loadLightmap(meshes,new java.io.ByteArrayInputStream(bad));}catch(java.io.IOException e){failed=true;}ok(failed,"bad lightmap accepted");}
        PortalMapData.loadLightmap(meshes,new java.io.ByteArrayInputStream(good));
    }
    public static void main(String[] args)throws Exception {
        Mesh[] meshes=load();lightmapChecks(meshes);PortalMapMesh mesh=new PortalMapMesh(meshes,2,true);mesh.setNearPlane(128);eq(mesh.parts(),15,"rooms");eq(mesh.triangles,3761,"world count, portal polygons must not draw");eq(mesh.textureUploads,1,"shared atlas uploaded more than once");
        PortalMapVisibility selector=(PortalMapVisibility)get(mesh,"selector");graphMatchesEngine(meshes,selector);focusedSelectorTests(meshes);
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();pose.setPosition(-76338,-1033,-102034);
        List<Graphics3D.Draw> off=draw(mesh,false,target,pose,240,320,212);eq(off.size(),15,"OFF full15");eq(mesh.submittedTriangles,3761,"OFF total");
        for(int r=0;r<15;r++){
            StaticRoomBvhMesh frozen=new StaticRoomBvhMesh(meshes[r],2,true);frozen.setNearPlane(128);frozen.setCullingEnabled(false);Graphics3D.history.clear();frozen.render(target,pose,240,320,212,212,40000);sameA(capture(),Collections.singletonList(off.get(r)));frozen.dispose();
        }
        int minRooms=15,maxRooms=0;int va=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads,indices=TriangleStripArray.created,pruned=0,outside=0;
        int[][] points={{-76338,-1033,-102034},{-75600,0,-70000},{-46000,0,-57000},{-27000,0,-96000},{-110000,0,-120000},{1000000,0,1000000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15){
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);
            off=draw(mesh,false,target,pose,240,320,212);float[] camera=Graphics3D.cameraTransform.matrix.clone();
            List<Graphics3D.Draw> on=draw(mesh,true,target,pose,240,320,212);ok(Arrays.equals(camera,Graphics3D.cameraTransform.matrix),"toggle moved camera");selectorOracle(mesh,240,320);
            List<Graphics3D.Draw> expected=new ArrayList<Graphics3D.Draw>();int t=0,v=0;
            for(int r=0;r<15;r++)if((mesh.roomMask&(1<<r))!=0){expected.add(off.get(r));t+=triangles(meshes[r]);v+=corners(meshes[r]);}
            sameA(expected,on);eq(mesh.submittedTriangles,t,"ON T");eq(mesh.submittedBufferVertices,v,"ON VB");eq(mesh.draws,expected.size(),"ON calls");eq(mesh.selectedRooms,expected.size(),"room HUD");
            if(mesh.currentRoom>=0){minRooms=Math.min(minRooms,mesh.selectedRooms);maxRooms=Math.max(maxRooms,mesh.selectedRooms);}if(mesh.selectedRooms<15)pruned++;if(mesh.currentRoom<0)outside++;sameA(on,draw(mesh,true,target,pose,240,320,212));if(mesh.currentRoom>=0)ok(mesh.selectionCached,"stationary selection not cached");cases++;
        }
        for(int[] size:new int[][]{{241,321},{321,241},{240,340},{320,240}}){
            int w=size[0],h=size[1];double tangent=Math.tan(74*Math.PI/360);int fx=(int)((w/2)/tangent)*h/w,fy=(int)((h/2)/tangent);Graphics screenSize=Image.createImage(w,h).getGraphics();
            for(int yaw=0;yaw<360;yaw+=90){pose.setRotY(yaw);pose.setPosition(-76338,-1033,-102034);List<Graphics3D.Draw> full=draw(mesh,false,screenSize,pose,w,h,fx,fy);List<Graphics3D.Draw> selected=draw(mesh,true,screenSize,pose,w,h,fx,fy);selectorOracle(mesh,w,h,fx,fy);List<Graphics3D.Draw> expected=new ArrayList<Graphics3D.Draw>();for(int r=0;r<15;r++)if((mesh.roomMask&(1<<r))!=0)expected.add(full.get(r));sameA(expected,selected);cases++;}
        }
        ok(pruned>0,"selector never culled anything");ok(maxRooms>1,"multiple visible rooms never selected");ok(outside>0,"outside case untested");eq(VertexArray.created,va,"hot VA allocation");eq(VertexArray.sets,sets,"hot attribute upload");eq(Graphics3D.uploads,uploads,"hot texture upload");eq(TriangleStripArray.created,indices,"hot index build");
        pose.setRotY(0);pose.setPosition(-76338,-1033,-102034);draw(mesh,true,target,pose,240,320,212);System.out.println("Desktop API counts ONLY: ON rooms="+mesh.selectedRooms+" at="+mesh.currentRoom+" T="+mesh.submittedTriangles+" DC="+mesh.draws+" VB="+mesh.submittedBufferVertices+" P="+mesh.portalTests);
        draw(mesh,false,target,pose,240,320,212);System.out.println("Desktop API counts ONLY: OFF rooms="+mesh.selectedRooms+" T="+mesh.submittedTriangles+" DC="+mesh.draws+" VB="+mesh.submittedBufferVertices);
        for(boolean mode:new boolean[]{false,true})for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}){
            mesh.setPortalsEnabled(mode);System.setProperty(flag,"true");boolean failed=false;try{mesh.render(target,pose,240,320,212,212,40000);}catch(Throwable e){failed=true;}finally{System.clearProperty(flag);}ok(failed && Graphics3D.target==null,"target leak "+flag);
        }
        Class<?> cls=Class.forName("code.Rendering.PortalMapPreview$Viewer");Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);Object viewer=ctor.newInstance(new PortalMapPreview());call(viewer,"load",new Class[0],new Object[0]);set(viewer,"visible",Boolean.TRUE);set(viewer,"running",Boolean.TRUE);Graphics screen=Image.createImage(240,340).getGraphics();paint(viewer,screen);ok(get(viewer,"error")==null,"UI paint error");int x=(Integer)get(viewer,"x"),yaw=(Integer)get(viewer,"yaw");Object surface=get(viewer,"surface");
        set(viewer,"turn",1);set(viewer,"move",1);key(viewer,51);eq((Integer)get(viewer,"turn"),0,"turn not frozen");eq((Integer)get(viewer,"move"),0,"move not frozen");ok((Boolean)get(viewer,"resetRequested"),"warmup not reset");paint(viewer,screen);PortalMapMesh room=(PortalMapMesh)get(viewer,"nativeRoom");ok(!room.isPortalsEnabled(),"3 not OFF in renderer");key(viewer,49);paint(viewer,screen);ok(room.isPortalsEnabled(),"1 not ON");key(viewer,48);paint(viewer,screen);ok(!room.isPortalsEnabled(),"0 not toggle");eq((Integer)get(viewer,"x"),x,"toggle moved x");eq((Integer)get(viewer,"yaw"),yaw,"toggle moved yaw");ok(surface==get(viewer,"surface"),"toggle target changed");call(viewer,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(viewer,screen);eq(Graphics3D.history.size(),0,"hidden render");call(viewer,"release",new Class[0],new Object[0]);
        PortalMapStats stats=new PortalMapStats();stats.reset(0);ok(!stats.record(500,500,520,521,522),"warmup");ok(!stats.record(1000,1000,1020,1021,1022),"anchor");ok(!stats.record(2000,2000,2020,2021,2022),"short window");ok(stats.record(4000,4000,4020,4021,4022),"window");eq(stats.fps10,6,"FPS fractional");eq(stats.meanNative,20,"N");eq(stats.meanInterval,1500,"I");
        mesh.dispose();boolean failed=false;try{mesh.setPortalsEnabled(true);}catch(IllegalStateException e){failed=true;}ok(failed,"disposed toggle");
        System.out.println("PASS PortalMap: "+checks+" checks / "+cases+" camera cases, pruned="+pruned+", outside="+outside+", in-world room range="+minRooms+".."+maxRooms+". Not a native rasterizer or phone FPS.");
    }
}
