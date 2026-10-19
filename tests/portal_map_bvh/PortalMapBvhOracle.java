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
final class PortalMapBvhOracle {
    private static void check(boolean ok,String message){PortalMapBvhTest.ok(ok,message);}
    private static double[] window;
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
    private static double distance(double[] p,int plane){
        return plane==0?p[0]-window[0]*p[3]:plane==1?window[2]*p[3]-p[0]:plane==2?p[1]-window[1]*p[3]:plane==3?window[3]*p[3]-p[1]:plane==4?p[3]+p[2]:p[3]-p[2];
    }
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
    static int verify(List<Graphics3D.Draw> whole,List<Graphics3D.Draw> reduced,float[] camera,Map<VertexBuffer,int[]> windows) {
        int removed=0;
        for(Graphics3D.Draw full:whole) {
            int[] box=windows.get(full.vertexBuffer);check(box!=null,"room window missing");
            window=new double[]{2.0*box[0]/full.viewportWidth-1,1-2.0*box[3]/full.viewportHeight,2.0*box[2]/full.viewportWidth-1,1-2.0*box[1]/full.viewportHeight};
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
}
