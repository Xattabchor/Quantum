package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.util.*;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.*;

/** Independent DOUBLE homogeneous triangle clipping, not the production AABB math.
 * Checks that every removed face is truly outside the original native frustum.
 * No claim about GPU occlusion, rasterized pixels or actual vertex-driver work.
 */
final class StaticRoomCullTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static double[][] inverse(float[] source) {
        double[][] m=new double[4][8];
        for(int r=0;r<4;r++){for(int c=0;c<4;c++)m[r][c]=source[r*4+c];m[r][r+4]=1;}
        for(int c=0;c<4;c++) {
            int pivot=c;for(int r=c+1;r<4;r++)if(Math.abs(m[r][c])>Math.abs(m[pivot][c]))pivot=r;
            double[] swap=m[c];m[c]=m[pivot];m[pivot]=swap;
            check(Math.abs(m[c][c])>1e-12,"test singular camera");double divisor=m[c][c];
            for(int k=0;k<8;k++)m[c][k]/=divisor;
            for(int r=0;r<4;r++)if(r!=c){double factor=m[r][c];for(int k=0;k<8;k++)m[r][k]-=m[c][k]*factor;}
        }
        double[][] result=new double[4][4];for(int r=0;r<4;r++)for(int c=0;c<4;c++)result[r][c]=m[r][c+4];return result;
    }
    private static double[][] clipMatrix(float[] camera,float[] projection) {
        double[][] inv=inverse(camera),out=new double[4][4];
        for(int r=0;r<4;r++)for(int c=0;c<4;c++)for(int k=0;k<4;k++)out[r][c]+=projection[r*4+k]*inv[k][c];
        return out;
    }
    private static double[] transform(double[][] clip,double x,double y,double z) {
        double[] out=new double[4];for(int r=0;r<4;r++)out[r]=clip[r][0]*x+clip[r][1]*y+clip[r][2]*z+clip[r][3];return out;
    }
    private static double distance(double[] p,int plane){return p[3]+((plane&1)==0?1:-1)*p[plane/2];}
    private static boolean triangleVisible(Graphics3D.Draw d,int a,int b,int c,double[][] clip) {
        List<double[]> poly=new ArrayList<double[]>();
        for(int v:new int[]{a,b,c})poly.add(transform(clip,d.positions[v*3]*d.scale+d.bias[0],d.positions[v*3+1]*d.scale+d.bias[1],d.positions[v*3+2]*d.scale+d.bias[2]));
        for(int plane=0;plane<6 && !poly.isEmpty();plane++) {
            List<double[]> output=new ArrayList<double[]>();double[] previous=poly.get(poly.size()-1);double dp=distance(previous,plane);
            for(double[] point:poly) {
                double dc=distance(point,plane);
                if((dp>=0)!=(dc>=0)) {
                    double t=dp/(dp-dc);double[] crossing=new double[4];for(int k=0;k<4;k++)crossing[k]=previous[k]+(point[k]-previous[k])*t;output.add(crossing);
                }
                if(dc>=0)output.add(point);previous=point;dp=dc;
            }
            poly=output;
        }
        return !poly.isEmpty();
    }
    static int verify(List<Graphics3D.Draw> whole,List<Graphics3D.Draw> reduced,float[] camera) {
        int removed=0;
        for(Graphics3D.Draw full:whole) {
            Graphics3D.Draw selected=null;
            for(Graphics3D.Draw d:reduced)if(d.vertexBuffer==full.vertexBuffer){check(selected==null,"duplicate packet");selected=d;}
            if(selected!=null) {
                check(Arrays.equals(full.positions,selected.positions) && Arrays.equals(full.coords,selected.coords)
                        && Arrays.equals(full.colors,selected.colors) && Arrays.equals(full.bias,selected.bias)
                        && full.scale==selected.scale && full.uvScale==selected.uvScale,"geometry/attribute upload changed");
                check(full.source==selected.source && full.depthTest==selected.depthTest && full.depthWrite==selected.depthWrite
                        && full.blend==selected.blend && full.threshold==selected.threshold && full.function==selected.function
                        && full.cull==selected.cull && full.winding==selected.winding && full.perspective==selected.perspective
                        && Arrays.equals(full.projection,selected.projection),"native state changed");
            }
            double[][] clip=clipMatrix(camera,full.projection);int fullOffset=0,selectedOffset=0,selectedStrip=0;
            for(int length:full.lengths) {
                boolean kept=selected!=null && selectedStrip<selected.lengths.length && selected.indices[selectedOffset]==full.indices[fullOffset];
                if(kept) {
                    check(selected.lengths[selectedStrip++]==length,"partial face clipping/strip changed");
                    for(int k=0;k<length;k++)check(selected.indices[selectedOffset+k]==full.indices[fullOffset+k],"face/triangle order changed");
                    selectedOffset+=length;
                } else {
                    for(int k=2;k<length;k++)check(!triangleVisible(full,full.indices[fullOffset+k-2],full.indices[fullOffset+k-1],full.indices[fullOffset+k],clip),"VISIBLE triangle removed by AABB culling");
                    removed+=length-2;
                }
                fullOffset+=length;
            }
            check(selected==null || selectedStrip==selected.lengths.length && selectedOffset==selected.indices.length,"extra or reordered face");
        }
        for(Graphics3D.Draw selected:reduced){boolean found=false;for(Graphics3D.Draw full:whole)if(full.vertexBuffer==selected.vertexBuffer)found=true;check(found,"new geometry appeared");}
        return removed;
    }
    private static void render(StaticRoomMesh mesh,Matrix camera,boolean cull,Image surface) {
        mesh.setCullingEnabled(cull);mesh.render(surface.getGraphics(),camera,240,340,225,225,68500);
    }
    static void run(Mesh source,Texture river,RawImage[] frames)throws Exception {
        river.rImg=frames[0]; // Fixture's animMIP[0] aliases this wrapper; restore before prewarm.
        StaticRoomMesh room=new StaticRoomMesh(source,2,true);Matrix pose=new Matrix();Image target=Image.createImage(240,340);
        int samples=0,min=4750,max=0,startSamples=0,startMin=4750,startMax=0,startYaw0=0;long sum=0,draws=0,startSum=0;
        int arrays=VertexArray.created,sets=VertexArray.sets,textures=Graphics3D.uploads;
        int[][] points={{-77706,-1209,-103802},{(source.minX()+source.maxX())/2,0,(source.minZ()+source.maxZ())/2},{source.minX()-10000,5000,source.minZ()-10000}};
        for(int[] point:points)for(int pitch:new int[]{-30,0,30})for(int yaw=0;yaw<360;yaw+=15)for(int frame=0;frame<4;frame++) {
            pose.setRotX(pitch);pose.rotY(yaw);pose.setPosition(point[0],point[1],point[2]);river.rImg=frames[frame];
            Graphics3D.history.clear();render(room,pose,false,target);
            List<Graphics3D.Draw> whole=new ArrayList<Graphics3D.Draw>(Graphics3D.history);float[] camera=Graphics3D.cameraTransform.matrix.clone();
            Graphics3D.history.clear();render(room,pose,true,target);
            int removed=verify(whole,new ArrayList<Graphics3D.Draw>(Graphics3D.history),camera);
            check(room.draws<=3 && room.submittedTriangles==4750-removed,"submitted triangle/draw counters");
            if(point==points[0] && pitch==0) {
                startSamples++;startSum+=room.submittedTriangles;startMin=Math.min(startMin,room.submittedTriangles);startMax=Math.max(startMax,room.submittedTriangles);
                if(yaw==0 && frame==0)startYaw0=room.submittedTriangles;
            }
            min=Math.min(min,room.submittedTriangles);max=Math.max(max,room.submittedTriangles);sum+=room.submittedTriangles;draws+=room.draws;samples++;
            int indices=TriangleStripArray.created,masks=room.maskUploads;
            Graphics3D.history.clear();render(room,pose,true,target);
            check(indices==TriangleStripArray.created && masks==room.maskUploads,"stationary mask rebuilt native indices");
            check(VertexArray.created==arrays && VertexArray.sets==sets && Graphics3D.uploads==textures,"culling uploads geometry or textures");
        }
        check(sum<samples*4750L,"real map culling removed nothing");
        java.lang.reflect.Field parts=StaticRoomMesh.class.getDeclaredField("parts");parts.setAccessible(true);
        for(Object part:(Vector)parts.get(room)) {
            java.lang.reflect.Field cache=part.getClass().getDeclaredField("masks");cache.setAccessible(true);
            check(((Object[])cache.get(part)).length==2,"mask cache unbounded");
        }
        System.out.println("CULL_RESULT samples="+samples+" totalTriangles="+room.triangles+" minSubmitted="+min+" maxSubmitted="+max+" sumSubmitted="+sum+" sumDraws="+draws+" maskUploads="+room.maskUploads);
        System.out.println("PASS 864 real poses: every omitted face independently double-clipped outside native frustum / remaining original order+XYZ+UV+RGBA+state / <=3 draws / warm fixed mask no native uploads or allocations");
        System.out.println("CULL_START samples="+startSamples+" minSubmitted="+startMin+" maxSubmitted="+startMax+" sumSubmitted="+startSum+" yaw0Submitted="+startYaw0);
        room.dispose();randomBounds();maskFailure(source,river,frames);

    }
    private static void maskFailure(Mesh source,Texture river,RawImage[] frames) {
        river.rImg=frames[0];StaticRoomMesh room=new StaticRoomMesh(source,2,true);Matrix camera=new Matrix();camera.setPosition(-77706,-1209,-103802);
        Image target=Image.createImage(240,340);System.setProperty("canvas.indices.fail","true");
        try{render(room,camera,true,target);}finally{System.clearProperty("canvas.indices.fail");}
        check(room.isCullingFallbackActive() && room.draws==3 && room.submittedTriangles==4750 && Graphics3D.target==null,"mask OOM did not restore complete native room");
        int indices=TriangleStripArray.created;render(room,camera,true,target);
        check(indices==TriangleStripArray.created && room.submittedTriangles==4750,"mask OOM repeated allocation attempts or omitted faces");
        room.dispose();System.out.println("PASS index-mask OOM before target binding -> cached WHOLE native room / no CPU / no missing faces / no repeated allocations");
    }
    private static void randomBounds() {
        Random random=new Random(728163);StaticRoomVisibility visibility=new StaticRoomVisibility();Matrix pose=new Matrix();int[] bounds=new int[6];
        for(int sample=0;sample<10000;sample++) {
            pose.setRotX(random.nextInt(120)-60);pose.rotY(random.nextInt(360));pose.setPosition(random.nextInt(400001)-200000,random.nextInt(400001)-200000,random.nextInt(400001)-200000);
            float[] m={pose.m00/16384f,pose.m01/16384f,pose.m02/16384f,pose.m03,pose.m10/16384f,pose.m11/16384f,pose.m12/16384f,pose.m13,pose.m20/16384f,pose.m21/16384f,pose.m22/16384f,pose.m23,0,0,0,1};
            // Include non-orthonormal camera transforms to catch transpose-only inverses.
            m[0]*=1.01f;m[5]*=0.99f;m[2]+=0.001f;
            float px=1.875f,py=450f/340,pz=-68501f/68499,pw=-137000f/68499;
            visibility.set(m,px,py,pz,pw);
            for(int k=0;k<3;k++){bounds[k]=random.nextInt(600001)-300000;bounds[k+3]=bounds[k]+random.nextInt(20000);}
            if(visibility.outside(bounds,0)) {
                double[][] clip=clipMatrix(m,new float[]{px,0,0,0,0,py,0,0,0,0,pz,pw,0,0,-1,0});boolean outside=false;
                for(int plane=0;plane<6;plane++) {
                    boolean all=true;for(int corner=0;corner<8;corner++) {
                        double[] point=transform(clip,bounds[(corner&1)==0?0:3],bounds[(corner&2)==0?1:4],bounds[(corner&4)==0?2:5]);
                        if(distance(point,plane)>=0)all=false;
                    }
                    outside|=all;
                }
                check(outside,"AABB false rejection");
            }
        }
        visibility.set(new float[16],1,1,-1,-2);check(!visibility.outside(bounds,0),"singular camera must conservatively keep bounds");
        System.out.println("PASS 10000 random AABBs / independent double inverse+clip support / singular fallback retains geometry");
    }
}
