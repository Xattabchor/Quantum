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

/** Geometry-knob (F/L) + delivery contracts over native doubles; NOT raster/FPS. */
public final class RiverGeoTest {
    static long checks;static int cases;
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
        StaticRoomTexturesPreview.loadLightmap(meshes,RiverGeoTest.class.getResourceAsStream(config.get("LIGHTMAP")));
        StaticRoomTexturesPreview.restoreDrawOrder(meshes[0],RiverGeoTest.class.getResourceAsStream("/maps/kordos/kordos.draworder"));return meshes[0];
    }
    static int[] state(Graphics3D.Draw d){return new int[]{d.blend,d.function,d.filter,d.levelFilter,d.shading,d.cull,d.winding,d.perspective?1:0,d.depthTest?1:0,d.depthWrite?1:0,d.fog?1:0,d.lit?1:0,Float.floatToIntBits(d.threshold),d.source.image.width,d.source.image.height,d.viewportWidth,d.viewportHeight,d.colorComponents};}
    static List<Graphics3D.Draw> capture(){return new ArrayList<Graphics3D.Draw>(Graphics3D.history);}
    static boolean sameGeometry(Graphics3D.Draw x,Graphics3D.Draw y) {
        return Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors)
            && Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths)
            && x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias)
            && Arrays.equals(x.projection,y.projection) && x.positionCapacity==y.positionCapacity;
    }
    static boolean sameGeometryNoProjection(Graphics3D.Draw x,Graphics3D.Draw y) {
        return Arrays.equals(x.positions,y.positions) && Arrays.equals(x.coords,y.coords) && Arrays.equals(x.colors,y.colors)
            && Arrays.equals(x.indices,y.indices) && Arrays.equals(x.lengths,y.lengths)
            && x.scale==y.scale && x.uvScale==y.uvScale && Arrays.equals(x.bias,y.bias)
            && x.positionCapacity==y.positionCapacity;
    }
    static void sameA(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b) {
        eq(a.size(),b.size(),"BASE changed call count");
        for(int i=0;i<a.size();i++) {
            Graphics3D.Draw x=a.get(i),y=b.get(i);
            ok(sameGeometry(x,y),"BASE geometry changed at "+i);
            ok(Arrays.equals(state(x),state(y)) && Arrays.equals(x.source.image.pixels,y.source.image.pixels),"BASE state/texture changed at "+i);
        }
    }
    static void sameGeometryList(List<Graphics3D.Draw> a,List<Graphics3D.Draw> b,String label) {
        eq(a.size(),b.size(),label+" call count");
        for(int i=0;i<a.size();i++)ok(sameGeometry(a.get(i),b.get(i)),label+" geometry changed at "+i);
    }
    static List<int[]> strips(Graphics3D.Draw d) {
        List<int[]> out=new ArrayList<int[]>();int off=0;
        for(int k=0;k<d.lengths.length;k++){int[] run=new int[d.lengths[k]];System.arraycopy(d.indices,off,run,0,run.length);out.add(run);off+=run.length;}
        return out;
    }
    static void subsetOrdered(List<Graphics3D.Draw> small,List<Graphics3D.Draw> big,String label) {
        ok(small.size()<=big.size(),label+" call count grew");
        List<int[]> bigAll=new ArrayList<int[]>();for(Graphics3D.Draw d:big)bigAll.addAll(strips(d));
        int j=0,missing=0;
        for(Graphics3D.Draw d:small)for(int[] run:strips(d)) {
            while(true) {
                if(j>=bigAll.size()){missing++;break;}
                if(Arrays.equals(run,bigAll.get(j))){j++;break;}
                j++;
            }
        }
        eq(missing,0,label+" selection not an ordered subset");
    }
    static List<Graphics3D.Draw> draw(RiverGeoMesh room,Graphics target,Matrix pose,int w,int h,int fx,int fy,int far,int limit) {
        room.setRadiusLimit(limit);
        Graphics3D.history.clear();room.render(target,pose,w,h,fx,fy,far);ok(Graphics3D.target==null,"target leak");return capture();
    }
    static void counters(RiverGeoMesh room,int t,int s,int dc,int vb,String label) {
        eq(room.submittedTriangles,t,label+" triangles");eq(room.submittedCorners,s,label+" corners");eq(room.draws,dc,label+" draws");eq(room.submittedBufferVertices,vb,label+" VB");
    }

    static void sweep(Mesh source)throws Exception {
        StaticRoomPerspectiveMesh original=new StaticRoomPerspectiveMesh(source,2,true);original.setNearPlane(128);original.setCullingEnabled(true);original.setPerspectiveEnabled(true);
        RiverGeoMesh room=new RiverGeoMesh(source,2,true);room.setNearPlane(128);room.setCullingEnabled(true);room.setPerspectiveEnabled(true);
        eq(room.triangles,4750,"full triangles");eq(room.uploadedVertices,10032,"resident vertices");eq(room.parts(),3,"parts");
        ok(room.isTexturedEnabled() && !room.isFlatShadingEnabled() && !room.isMipmapsEnabled() && room.getRadiusLimit()==0,"default state");
        eq(room.getNearPlane(),128,"near");
        Texture river=source.getTexture().textures[2];RawImage[] frames=new RawImage[4];for(int i=0;i<4;i++)frames[i]=river.animMIP[i].rImg;
        Matrix pose=new Matrix();Graphics target=Image.createImage(240,320).getGraphics();Graphics small=Image.createImage(120,160).getGraphics();Graphics quarter=Image.createImage(60,80).getGraphics();
        int arrays=VertexArray.created,sets=VertexArray.sets,uploads=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=60)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];
            Graphics3D.history.clear();original.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> expected=capture();
            List<Graphics3D.Draw> base=draw(room,target,pose,240,320,212,212,60000,0);sameA(expected,base);
            eq(room.submittedTriangles,original.submittedTriangles,"BASE triangles");eq(room.submittedCorners,original.submittedCorners,"BASE corners");
            eq(room.submittedBufferVertices,original.submittedBufferVertices,"BASE VB");eq(room.visibilityTests,original.visibilityTests,"selector changed");
            eq(arrays,VertexArray.created,"hot vertex allocation");eq(sets,VertexArray.sets,"hot vertex upload");eq(uploads,Graphics3D.uploads,"hot texture upload");
            eq(room.getRadiusCuts(),0,"L0 must never radius-cut");
            cases++;
        }

        // Home pose knobs: ordered-subset selections, never a different face stream.
        pose.setRotY(0);pose.setPosition(points[0][0],points[0][1],points[0][2]);river.rImg=frames[2];
        List<Graphics3D.Draw> base=draw(room,target,pose,240,320,212,212,60000,0);
        int t=room.submittedTriangles,s=room.submittedCorners,dc=room.draws,vb=room.submittedBufferVertices,indices=TriangleStripArray.created,masks=room.maskUploads;
        draw(room,target,pose,240,320,212,212,60000,0);
        eq(indices,TriangleStripArray.created,"stationary indices rebuilt");eq(masks,room.maskUploads,"stationary mask rebuilt");

        // L rejects only whole cells, preserving strip order; no face ever appears outside the L0 selection.
        List<Graphics3D.Draw> l8=draw(room,target,pose,240,320,212,212,60000,8);subsetOrdered(l8,base,"L8");int t8=room.submittedTriangles;ok(t8<=t,"L8 grew triangles");
        List<Graphics3D.Draw> l16=draw(room,target,pose,240,320,212,212,60000,16);subsetOrdered(l16,base,"L16");subsetOrdered(l16,l8,"L16 vs L8 triangle stream");
        int t16=room.submittedTriangles;ok(t16<=t8,"L monotonic");ok(room.getRadiusCuts()>=0,"radius cuts counter");
        List<Graphics3D.Draw> restored=draw(room,target,pose,240,320,212,212,60000,0);sameA(base,restored);counters(room,t,s,dc,vb,"restore L0");

        // Far only shrinks the frustum; geometry it keeps is byte identical to F60K.
        List<Graphics3D.Draw> f40=draw(room,target,pose,240,320,212,212,40000,0);subsetOrdered(f40,base,"F40K");ok(room.submittedTriangles<=t,"F40K grew triangles");
        List<Graphics3D.Draw> f20=draw(room,target,pose,240,320,212,212,20000,0);subsetOrdered(f20,f40,"F20K vs F40K");ok(room.submittedTriangles<=t,"F20K grew triangles");
        List<Graphics3D.Draw> restoredFar=draw(room,target,pose,240,320,212,212,60000,0);sameA(base,restoredFar);counters(room,t,s,dc,vb,"restore F60K");
        // Witness both knobs actually engaging on this map: scan the six yaw headings at both anchors.
        boolean farCut=false,radCut=false;
        Matrix probe=new Matrix();
        outer:
        for(int[] point:points)for(int yaw=0;yaw<360;yaw+=60) {
            probe.setRotY(yaw);probe.setPosition(point[0],point[1],point[2]);
            draw(room,target,probe,240,320,212,212,20000,0);int tFar=room.submittedTriangles;
            draw(room,target,probe,240,320,212,212,60000,16);int tRad=room.submittedTriangles;
            draw(room,target,probe,240,320,212,212,60000,0);int tAll=room.submittedTriangles;
            if(tFar<tAll)farCut=true;if(tRad<tAll)radCut=true;
            if(farCut && radCut)break outer;
        }
        ok(farCut,"F20K never cut across probe headings");ok(radCut,"L16 never cut across probe headings");

        // Scale toggles stay geometry-identical at the default knobs.
        List<Graphics3D.Draw> half=draw(room,small,pose,120,160,106,106,60000,0);sameGeometryList(base,half,"S50");counters(room,t,s,dc,vb,"S50");
        for(Graphics3D.Draw d:half){eq(d.viewportWidth,120,"S50 viewport width");eq(d.viewportHeight,160,"S50 viewport height");}
        List<Graphics3D.Draw> quarterDraws=draw(room,quarter,pose,60,80,53,53,60000,0);sameGeometryList(base,quarterDraws,"S25");counters(room,t,s,dc,vb,"S25");
        for(Graphics3D.Draw d:quarterDraws){eq(d.viewportWidth,60,"S25 viewport width");eq(d.viewportHeight,80,"S25 viewport height");}

        // MIP toggle inherited from the pixel parent.
        room.setMipmapsEnabled(true);
        Graphics3D.history.clear();room.render(target,pose,240,320,212,212,60000);List<Graphics3D.Draw> mipDraws=capture();
        sameGeometryList(base,mipDraws,"MIP");counters(room,t,s,dc,vb,"MIP");
        for(Graphics3D.Draw d:mipDraws){eq(d.levelFilter,Texture2D.FILTER_NEAREST,"nearest mip level filter");eq(d.filter,Texture2D.FILTER_NEAREST,"nearest image filter");}
        room.setMipmapsEnabled(false);
        List<Graphics3D.Draw> restoredMip=draw(room,target,pose,240,320,212,212,60000,0);sameA(base,restoredMip);
        river.rImg=frames[0];room.dispose();original.dispose();
    }

    static void failures(Mesh source)throws Exception {
        RiverGeoMesh room=new RiverGeoMesh(source,2,true);room.setCullingEnabled(true);room.setNearPlane(128);
        Graphics target=Image.createImage(240,320).getGraphics();Matrix pose=new Matrix();pose.setPosition(-77706,-1209,-103802);
        draw(room,target,pose,240,320,212,212,60000,0);
        for(String flag:new String[]{"canvas.bind.fail","canvas.viewport.fail","clear.fail","m3g.oom","release.fail"}) {
            System.setProperty(flag,"true");boolean failed=false;
            try{draw(room,target,pose,240,320,212,212,60000,0);}catch(Throwable ex){failed=true;}finally{System.clearProperty(flag);}
            ok(failed && Graphics3D.target==null,"native failure target leak "+flag);
        }
        draw(room,target,pose,240,320,212,212,60000,0);room.dispose();boolean failed=false;
        try{room.render(target,pose,240,320,212,212,60000);}catch(IllegalStateException ex){failed=true;}
        ok(failed,"disposed room rendered");
    }

    static void upscaleNN(int[] src,int sw,int sh,int[] dst,int dw,int dh)throws Exception {
        Class viewer=Class.forName("code.Rendering.RiverGeoPreview$Viewer");
        Method m=viewer.getDeclaredMethod("upscaleNN",new Class[]{int[].class,int.class,int.class,int[].class,int.class,int.class});
        m.setAccessible(true);
        try{m.invoke(null,new Object[]{src,Integer.valueOf(sw),Integer.valueOf(sh),dst,Integer.valueOf(dw),Integer.valueOf(dh)});}
        catch(InvocationTargetException ex){throw (Exception)ex.getCause();}
    }
    static void upscaleChecks()throws Exception {
        int[] src={1,2,3,4,5,6},dst=new int[24];
        upscaleNN(src,3,2,dst,6,4);
        int[] expected={1,1,2,2,3,3, 1,1,2,2,3,3, 4,4,5,5,6,6, 4,4,5,5,6,6};
        for(int i=0;i<24;i++)eq(dst[i],expected[i],"3x2 -> 6x4 at "+i);
        int[] small={10,11,20,21},wide=new int[9];
        upscaleNN(small,2,2,wide,3,3);
        int[] expectedWide={10,10,11, 10,10,11, 20,20,21};
        for(int i=0;i<9;i++)eq(wide[i],expectedWide[i],"2x2 -> 3x3 at "+i);
        int[] quarter=new int[60*80],full=new int[240*320];
        for(int i=0;i<quarter.length;i++)quarter[i]=i*17;
        upscaleNN(quarter,60,80,full,240,320);
        for(int y=0;y<320;y+=37)for(int x=0;x<240;x+=31) {
            eq(full[y*240+x],quarter[(y*80/320)*60+(x*60/240)],"60x80 -> 240x320 at "+x+","+y);
            cases++;
        }
        boolean rejected=false;
        try{upscaleNN(small,3,3,new int[16],2,2);}catch(IllegalArgumentException ex){rejected=true;}
        ok(rejected,"shrink rejected");
        rejected=false;
        try{upscaleNN(small,2,2,new int[4],3,3);}catch(IllegalArgumentException ex){rejected=true;}
        ok(rejected,"short buffer rejected");
    }

    static Object getStatic(Class type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    static Object viewer()throws Exception {
        ((Hashtable)getStatic(Asset.class,"table")).clear();Image.resourceReads.clear();Image.missingResources.clear();
        Class c=Class.forName("code.Rendering.RiverGeoPreview$Viewer");Constructor ctor=c.getDeclaredConstructor(RiverGeoPreview.class);ctor.setAccessible(true);
        Object v=ctor.newInstance(new RiverGeoPreview());call(v,"load",new Class[0],new Object[0]);set(v,"running",Boolean.TRUE);set(v,"visible",Boolean.TRUE);return v;
    }
    static void resetScreen(Graphics screen){screen.stringCalls=0;screen.rectCalls=0;screen.lastString=null;Graphics3D.history.clear();}
    static boolean bool(Object v,String name)throws Exception{return ((Boolean)get(v,name)).booleanValue();}
    static int integer(Object v,String name)throws Exception{return ((Integer)get(v,name)).intValue();}
    static void viewerChecks()throws Exception {
        Object v=viewer();RiverGeoMesh room=(RiverGeoMesh)get(v,"nativeRoom");
        eq(Image.resourceReads.size(),6,"cold texture reads");ok(Image.missingResources.isEmpty(),"missing textures");
        eq(integer(v,"scaleIndex"),0,"default scale");eq(integer(v,"farIndex"),0,"default far");eq(integer(v,"limitIndex"),0,"default limit");
        ok(!bool(v,"upscale") && !bool(v,"mip"),"default toggles");eq(room.getRadiusLimit(),0,"default L");
        eq(room.getNearPlane(),128,"near");eq(integer(v,"far"),60000,"far");eq(DirectX7.drDist,60000,"drDist");
        Graphics screen=Image.createImage(240,340).getGraphics();
        int draws=Graphics.drawImageCalls,blits=Graphics.drawRGBCalls,reads=Image.getRGBCalls;
        resetScreen(screen);paint(v,screen);ok(get(v,"error")==null,"cold paint failed");
        eq(screen.stringCalls,2,"live HUD strings");eq(screen.rectCalls,1,"live HUD rects at S100 DIR");
        eq(Graphics.drawImageCalls,draws+1,"S100 DIR image copy");eq(Graphics.drawRGBCalls,blits,"S100 DIR kept drawRGB unused");eq(Image.getRGBCalls,reads,"S100 DIR no readback");
        ok(((String)get(v,"heading")).startsWith("S100 DIR F60K L0 B"),"default heading");
        ok(((String)get(v,"timings")).startsWith("T -"),"warming timings carry T slot");
        eq(integer(v,"renderWidth"),240,"S100 width");eq(integer(v,"renderHeight"),340,"S100 height");
        int x=integer(v,"x"),y=integer(v,"y"),z=integer(v,"z"),yaw=integer(v,"yaw");

        key(v,51);resetScreen(screen);paint(v,screen);
        ok(bool(v,"upscale"),"key3 upscale on");ok(((String)get(v,"heading")).startsWith("S100 UP F60K L0 B"),"S100 UP heading");
        eq(Graphics.drawImageCalls,draws+2,"S100 UP pipeline control");eq(Graphics.drawRGBCalls,blits,"S100 UP kept drawRGB unused");

        key(v,55);resetScreen(screen);paint(v,screen);
        eq(integer(v,"farIndex"),1,"key7 F40K");eq(integer(v,"far"),40000,"F40K far");eq(DirectX7.drDist,40000,"F40K drDist");
        ok(((String)get(v,"heading")).startsWith("S100 UP F40K L0 B"),"F40K heading");
        key(v,55);resetScreen(screen);paint(v,screen);
        eq(integer(v,"far"),20000,"F20K far");ok(((String)get(v,"heading")).startsWith("S100 UP F20K L0 B"),"F20K heading");
        key(v,55);resetScreen(screen);paint(v,screen);
        eq(integer(v,"far"),60000,"F60K restored");eq(DirectX7.drDist,60000,"F60K drDist");

        key(v,57);resetScreen(screen);paint(v,screen);
        eq(integer(v,"limitIndex"),1,"key9 L8");eq(room.getRadiusLimit(),8,"L8 room");ok(((String)get(v,"heading")).startsWith("S100 UP F60K L8 B"),"L8 heading");
        key(v,57);paint(v,screen);eq(room.getRadiusLimit(),16,"L16 room");ok(((String)get(v,"heading")).startsWith("S100 UP F60K L16 B"),"L16 heading");
        key(v,57);paint(v,screen);eq(room.getRadiusLimit(),0,"L0 room");

        key(v,48);resetScreen(screen);draws=Graphics.drawImageCalls;blits=Graphics.drawRGBCalls;reads=Image.getRGBCalls;paint(v,screen);
        ok(((String)get(v,"heading")).startsWith("S75 UP F60K L0 B"),"S75 UP heading");
        eq(Graphics.drawRGBCalls,blits+1,"S75 UP one drawRGB");eq(Graphics.drawImageCalls,draws,"S75 UP no image copy");eq(Image.getRGBCalls,reads+1,"S75 UP one readback");
        eq(screen.rgbX,0,"drawRGB x");eq(screen.rgbY,0,"drawRGB y");eq(screen.rgbW,240,"drawRGB width");eq(screen.rgbH,340,"drawRGB height");ok(!screen.rgbA,"drawRGB opaque");
        eq(screen.rectCalls,1,"S75 UP no black bars");
        eq(((int[])get(v,"srcBuf")).length,180*255,"S75 src buffer");eq(((int[])get(v,"dstBuf")).length,240*340,"dst buffer");
        List<Graphics3D.Draw> upDraws=capture();
        key(v,51);resetScreen(screen);draws=Graphics.drawImageCalls;blits=Graphics.drawRGBCalls;reads=Image.getRGBCalls;paint(v,screen);
        ok(!bool(v,"upscale"),"key3 upscale off");
        eq(Graphics.drawImageCalls,draws+1,"S75 DIR image copy");eq(Graphics.drawRGBCalls,blits,"S75 DIR no drawRGB");eq(Image.getRGBCalls,reads,"S75 DIR no readback");
        eq(screen.rectCalls,3,"S75 DIR black bars + HUD");
        List<Graphics3D.Draw> dirDraws=capture();sameGeometryList(dirDraws,upDraws,"delivery");
        eq(integer(v,"renderWidth"),180,"S75 width");eq(integer(v,"renderHeight"),255,"S75 height");

        key(v,48);key(v,51);resetScreen(screen);paint(v,screen);
        ok(((String)get(v,"heading")).startsWith("S50 UP F60K L0 B"),"S50 UP heading");
        eq(((int[])get(v,"srcBuf")).length,120*170,"S50 src buffer");

        key(v,49);resetScreen(screen);paint(v,screen);
        ok(integer(v,"scaleIndex")==0 && integer(v,"farIndex")==0 && integer(v,"limitIndex")==0 && !bool(v,"upscale") && !bool(v,"mip"),"key1 BASE reset");
        eq(integer(v,"far"),60000,"BASE far");eq(room.getRadiusLimit(),0,"BASE L");

        // Combined working point: S50 UP F20K L16 must paint without error and keep pose.
        key(v,48);key(v,48);key(v,51);key(v,55);key(v,55);key(v,57);key(v,57);resetScreen(screen);paint(v,screen);
        ok(((String)get(v,"heading")).startsWith("S50 UP F20K L16 B"),"combo heading");ok(get(v,"error")==null,"combo paint failed");
        eq(integer(v,"far"),20000,"combo far");eq(room.getRadiusLimit(),16,"combo L");
        key(v,49);resetScreen(screen);paint(v,screen);

        eq(integer(v,"x"),x,"toggles moved x");eq(integer(v,"y"),y,"toggles moved y");eq(integer(v,"z"),z,"toggles moved z");eq(integer(v,"yaw"),yaw,"toggles moved yaw");
        eq(((RiverGeoStats)get(v,"stats")).frames,0,"switch frame measured");
        set(v,"x",Integer.valueOf(x+1000));set(v,"z",Integer.valueOf(z-1000));set(v,"yaw",Integer.valueOf(90));key(v,53);
        eq(integer(v,"x"),x,"home x");eq(integer(v,"z"),z,"home z");eq(integer(v,"yaw"),0,"home yaw");
        call(v,"hideNotify",new Class[0],new Object[0]);Graphics3D.history.clear();paint(v,screen);eq(Graphics3D.history.size(),0,"hidden render");
        call(v,"showNotify",new Class[0],new Object[0]);ok(bool(v,"resetRequested"),"show reset");
        RiverGeoStats stats=new RiverGeoStats();stats.reset(0);
        ok(!stats.record(500,500,520,521,522,room),"warm published");ok(!stats.record(1000,1000,1020,1021,1022,room),"anchor published");
        room.prepareMs=2;room.bindMs=3;room.clearMs=4;room.drawMs=5;room.releaseMs=6;
        ok(!stats.record(2000,2000,2020,2021,2022,room),"short window published");ok(stats.record(4000,4000,4020,4021,4022,room),"window missing");
        eq(stats.fps10,6,"stats FPS");eq(stats.meanNative,20,"stats N");eq(stats.meanCopy,1,"stats C");eq(stats.meanInterval,1500,"stats I");
        eq(stats.meanJ,2,"stats J");eq(stats.meanB,3,"stats B");eq(stats.meanK,4,"stats K");eq(stats.meanR,5,"stats R");eq(stats.meanE,6,"stats E");
        call(v,"release",new Class[0],new Object[0]);
    }

    public static void main(String[] args)throws Exception {
        Mesh source=load();sweep(source);failures(source);upscaleChecks();viewerChecks();
        System.out.println("PASS RiverGeo: "+checks+" checks / "+cases+" poses/river frames; geometry-knob subset contracts, delivery and stats windows. NOT device FPS or rasterization.");
    }
}
