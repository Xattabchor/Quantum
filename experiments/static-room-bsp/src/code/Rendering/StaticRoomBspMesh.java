package code.Rendering;

import code.Math.Matrix;
import code.Rendering.Meshes.Mesh;
import java.io.*;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** Offline split BSP painter. A=original GRID+Z, B=BSP no Z, C=same BSP+Z.
 * Only consecutive same-page spans are batched. NEVER regroup painter by texture.
 * Native attributes are cold; two exact ordered index selections are cached.
 */
public final class StaticRoomBspMesh {
    private StaticRoomBspGrid grid;
    private final Graphics3D graphics=Graphics3D.getInstance();
    private final javax.microedition.m3g.Camera camera=new javax.microedition.m3g.Camera();
    private final Transform projection=new Transform(),view=new Transform();
    private final Background background=new Background();
    private final float[] matrix=new float[16];
    private final StaticRoomVisibility visibility=new StaticRoomVisibility();
    private final Vector images=new Vector();
    private Material[] materials;
    private Page[] pages;
    private int[] children,spanRanges,bounds,spans,stack,order;
    private float[] planes;
    private Selection[] cache=new Selection[2];
    private int nextCache,mode,nodeCount,spanCount,maxDepth,texturePixels;
    private boolean disposed,culling=true;
    public int prepareMs,bindMs,clearMs,drawMs,releaseMs,submittedBufferVertices;
    public int submittedTriangles,submittedCorners,draws,visibilityTests,culledCells,maskUploads;
    public int totalTriangles,uploadedVertices,geometryUploads,textureUploads;
    private static final class NativeImage {RawImage source;Texture2D texture;float threshold;}
    private static final class Material {Texture source;RawImage frame;Appearance appearance;int mode;}
    private static final class Page {VertexBuffer vertices;int count,material;}
    private static final class Selection {int[] order,page,counts;TriangleStripArray[] indices;}

    public StaticRoomBspMesh(Mesh source,int quality,boolean perspective)throws IOException {
        if(source==null || source.getTexture()==null || source.getTexture().textures.length!=3)throw new IOException("BSP requires Tex3 map");
        try {
            grid=new StaticRoomBspGrid(source,quality,perspective);grid.setNearPlane(128);grid.setCullingEnabled(true);
            if(grid.triangles!=4750)throw new IOException("BSP source mismatch");
            materials=new Material[3];
            for(int i=0;i<3;i++){
                Texture t=source.getTexture().textures[i];Material m=new Material();m.source=t;m.mode=t.drawmode;
                if(m.mode!=0 && m.mode!=9 && m.mode!=13 && m.mode!=7)throw new IOException("BSP material");
                m.appearance=new Appearance();PolygonMode polygon=new PolygonMode();polygon.setCulling(PolygonMode.CULL_BACK);polygon.setWinding(PolygonMode.WINDING_CW);polygon.setShading(PolygonMode.SHADE_SMOOTH);polygon.setPerspectiveCorrectionEnable(perspective && t.perspectiveCorrection);m.appearance.setPolygonMode(polygon);
                CompositingMode comp=new CompositingMode();comp.setDepthTestEnable(false);comp.setDepthWriteEnable(false);comp.setBlending(m.mode==7?CompositingMode.MODULATE:CompositingMode.REPLACE);m.appearance.setCompositingMode(comp);
                NativeImage n=image(t.rImg);m.appearance.setTexture(0,n.texture);comp.setAlphaThreshold(n.threshold);m.frame=t.rImg;
                if(t.animMIP!=null)for(int f=0;f<t.animMIP.length;f++)image(t.animMIP[f].rImg);
                materials[i]=m;
            }
            load();
            background.setColor(0x203040);background.setColorClearEnable(true);background.setDepthClearEnable(false);
        }catch(IOException ex){dispose();throw ex;}catch(RuntimeException ex){dispose();throw ex;}catch(Error ex){dispose();throw ex;}
    }
    public int getMode(){return mode;}
    public static String modeName(int value){return value==0?"A GRID+Z":value==1?"B BSP-Z":"C BSP+Z";}
    public void setMode(int value){
        if(disposed)throw new IllegalStateException("disposed BSP");
        if(value<0 || value>2)throw new IllegalArgumentException("BSP mode");
        if(value==mode)return;
        boolean depth=value==2;
        for(int i=0;i<materials.length;i++){
            CompositingMode c=materials[i].appearance.getCompositingMode();c.setDepthTestEnable(depth);c.setDepthWriteEnable(depth && materials[i].mode!=7);
        }
        background.setDepthClearEnable(depth);mode=value;
    }
    public void setCullingEnabled(boolean enabled){culling=enabled;grid.setCullingEnabled(enabled);}
    private static void require(boolean condition,String why)throws IOException{if(!condition)throw new IOException("Invalid BSP: "+why);}
    private static boolean finite(float x){return !Float.isNaN(x) && !Float.isInfinite(x);}
    private void load()throws IOException {
        InputStream stream=StaticRoomBspMesh.class.getResourceAsStream("/maps/kordos/kordos.bsp");
        if(stream==null)throw new IOException("BSP asset missing");
        DataInputStream in=new DataInputStream(stream);
        try {
            require(in.readInt()==0x51425350 && in.readInt()==1,"header");require(in.readInt()==4750,"source triangles");
            nodeCount=in.readInt();int pageCount=in.readInt();spanCount=in.readInt();totalTriangles=in.readInt();maxDepth=in.readInt();
            require(nodeCount>0 && nodeCount<=8192 && pageCount>0 && pageCount<=1024 && spanCount>0 && spanCount<=32768 && totalTriangles>=4750 && totalTriangles<=100000 && maxDepth>0 && maxDepth<=120,"budgets");
            planes=new float[nodeCount*4];children=new int[nodeCount*2];spanRanges=new int[nodeCount*2];bounds=new int[nodeCount*6];spans=new int[spanCount*3];
            int[] parents=new int[nodeCount],depths=new int[nodeCount];depths[0]=1;int spanCursor=0;
            for(int n=0;n<nodeCount;n++){
                for(int k=0;k<4;k++){float f=in.readFloat();require(finite(f) && Math.abs(f)<8000001,"plane");planes[n*4+k]=f;}
                float x=planes[n*4],y=planes[n*4+1],z=planes[n*4+2];require(Math.abs(x*x+y*y+z*z-1)<0.0001f,"normal");
                for(int k=0;k<2;k++){int child=in.readInt();require(child==-1 || (child>n && child<nodeCount),"acyclic child");children[n*2+k]=child;if(child>=0){require(++parents[child]==1,"multiple parents");depths[child]=depths[n]+1;}}
                int first=in.readInt(),count=in.readInt();require(first==spanCursor && count>0 && count<=spanCount-first,"span range");spanRanges[n*2]=first;spanRanges[n*2+1]=count;spanCursor+=count;
                for(int k=0;k<6;k++){int b=in.readInt();require(b>=-8000004 && b<=8000004,"bounds budget");bounds[n*6+k]=b;}
                for(int k=0;k<3;k++)require(bounds[n*6+k]<=bounds[n*6+3+k],"inverted bounds");
                require(depths[n]>0 && depths[n]<=maxDepth,"depth");
            }
            require(spanCursor==spanCount,"unreferenced spans");
            for(int n=1;n<nodeCount;n++)require(parents[n]==1,"unreachable node");
            for(int n=0;n<nodeCount;n++)for(int k=0;k<2;k++){
                int child=children[n*2+k];if(child<0)continue;
                for(int a=0;a<3;a++)require(bounds[n*6+a]<=bounds[child*6+a] && bounds[n*6+3+a]>=bounds[child*6+3+a],"subtree bounds");
            }
            int[] covered=new int[pageCount];
            for(int s=0;s<spanCount;s++){
                int p=in.readInt(),first=in.readInt(),count=in.readInt();require(p>=0 && p<pageCount && first==covered[p] && count>0 && count%3==0 && first+count<=384,"page span");
                covered[p]+=count;spans[s*3]=p;spans[s*3+1]=first;spans[s*3+2]=count;
            }
            pages=new Page[pageCount];int vertices=0;
            for(int p=0;p<pageCount;p++){
                int material=in.readUnsignedByte(),count=in.readUnsignedShort();float step=in.readFloat();float[] bias={in.readFloat(),in.readFloat(),in.readFloat()};
                require(material<3 && count>0 && count<=384 && count%3==0 && count==covered[p] && finite(step) && step>=0.0625f && step<=256,"page header");
                for(int k=0;k<3;k++)require(finite(bias[k]) && Math.abs(bias[k])<=8000000,"page origin");
                short[] xyz=new short[count*3],uv=new short[count*2];byte[] rgb=new byte[count*4];
                for(int i=0;i<xyz.length;i++)xyz[i]=in.readShort();for(int i=0;i<uv.length;i++)uv[i]=in.readShort();
                for(int i=0;i<count;i++){in.readFully(rgb,i*4,3);rgb[i*4+3]=(byte)255;}
                // Verify the actual packed positions are conservatively bounded.
                for(int n=0;n<nodeCount;n++)for(int s=spanRanges[n*2];s<spanRanges[n*2]+spanRanges[n*2+1];s++)if(spans[s*3]==p){
                    for(int v=spans[s*3+1];v<spans[s*3+1]+spans[s*3+2];v++)for(int k=0;k<3;k++){
                        float pos=xyz[v*3+k]*step+bias[k];require(pos>=bounds[n*6+k] && pos<=bounds[n*6+3+k],"packed bounds");
                    }
                }
                VertexArray positions=new VertexArray(count,3,2),coords=new VertexArray(count,2,2),colors=new VertexArray(count,4,1);
                positions.set(0,count,xyz);coords.set(0,count,uv);colors.set(0,count,rgb);
                Page page=new Page();page.material=material;page.count=count;page.vertices=new VertexBuffer();page.vertices.setPositions(positions,step,bias);page.vertices.setTexCoords(0,coords,1.0f/32768,null);page.vertices.setColors(colors);pages[p]=page;
                vertices+=count;geometryUploads+=3;
            }
            require(vertices==totalTriangles*3 && in.read()==-1,"triangle count/trailing bytes");uploadedVertices=vertices;
            stack=new int[maxDepth*3+8];order=new int[spanCount];
        }finally{in.close();}
    }
    private NativeImage image(RawImage raw){
        for(int i=0;i<images.size();i++){NativeImage n=(NativeImage)images.elementAt(i);if(n.source==raw)return n;}
        Object cap=Graphics3D.getProperties().get("maxTextureDimension");int max=cap instanceof Integer?((Integer)cap).intValue():256;
        if(raw==null || raw.img==null || raw.w<1 || raw.h<1 || (raw.w&(raw.w-1))!=0 || (raw.h&(raw.h-1))!=0 || raw.w>max || raw.h>max || (long)raw.w*raw.h>raw.img.length || (long)texturePixels+(long)raw.w*raw.h>262144)throw new IllegalArgumentException("BSP texture budget/format");
        byte[] rgba=new byte[raw.w*raw.h*4];boolean cutout=false;
        for(int i=0;i<raw.w*raw.h;i++){int p=raw.img[i];rgba[i*4]=(byte)(p>>16);rgba[i*4+1]=(byte)(p>>8);rgba[i*4+2]=(byte)p;rgba[i*4+3]=(byte)(p==0?0:255);if(p==0)cutout=true;}
        NativeImage n=new NativeImage();n.source=raw;n.threshold=cutout?0.5f:0;n.texture=new Texture2D(new Image2D(Image2D.RGBA,raw.w,raw.h,rgba));n.texture.setWrapping(Texture2D.WRAP_REPEAT,Texture2D.WRAP_REPEAT);n.texture.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);n.texture.setBlending(Texture2D.FUNC_MODULATE);images.addElement(n);texturePixels+=raw.w*raw.h;textureUploads++;return n;
    }
    private Selection select(){
        int top=0,length=0;stack[top++]=0;
        while(top>0){
            int n=stack[--top];
            if(n<0){n=~n;int first=spanRanges[n*2],count=spanRanges[n*2+1];for(int s=first;s<first+count;s++)order[length++]=s;continue;}
            if(culling){visibilityTests++;if(visibility.outside(bounds,n*6)){culledCells++;continue;}}
            // Use the SAME shifted/quantized camera origin sent to M3G.
            int p=n*4;float side=planes[p]*matrix[3]+planes[p+1]*matrix[7]+planes[p+2]*matrix[11]+planes[p+3];
            int near=children[n*2+(side>=0?0:1)],far=children[n*2+(side>=0?1:0)];
            if(near>=0)stack[top++]=near;stack[top++]=~n;if(far>=0)stack[top++]=far;
        }
        for(int c=0;c<cache.length;c++){
            Selection hit=cache[c];if(hit==null || hit.order.length!=length)continue;boolean same=true;
            for(int i=0;i<length;i++)if(hit.order[i]!=order[i]){same=false;break;}if(same)return hit;
        }
        Selection result=new Selection();result.order=new int[length];System.arraycopy(order,0,result.order,0,length);
        int runs=0,last=-1;for(int i=0;i<length;i++){int p=spans[order[i]*3];if(p!=last){runs++;last=p;}}
        result.page=new int[runs];result.counts=new int[runs];result.indices=new TriangleStripArray[runs];
        for(int i=0,r=0;i<length;r++){
            int begin=i,page=spans[order[i]*3],count=0;
            do{count+=spans[order[i]*3+2];i++;}while(i<length && spans[order[i]*3]==page);
            int[] indices=new int[count],strips=new int[count/3];int at=0;
            for(int j=begin;j<i;j++){int s=order[j]*3;for(int v=spans[s+1];v<spans[s+1]+spans[s+2];v++)indices[at++]=v;}
            for(int j=0;j<strips.length;j++)strips[j]=3;
            result.page[r]=page;result.counts[r]=count;result.indices[r]=new TriangleStripArray(indices,strips);maskUploads++;
        }
        cache[nextCache]=result;if(++nextCache==cache.length)nextCache=0;return result;
    }
    public void render(Graphics target,Matrix pose,int width,int height,int focalX,int focalY,int far){
        if(disposed)throw new IllegalStateException("disposed BSP");
        if(target==null || pose==null || width<=0 || height<=0 || focalX<=0 || focalY<=0 || far<=128)throw new IllegalArgumentException("camera/viewport");
        if(mode==0){
            grid.render(target,pose,width,height,focalX,focalY,far);
            prepareMs=grid.prepareMs;bindMs=grid.bindMs;clearMs=grid.clearMs;drawMs=grid.drawMs;releaseMs=grid.releaseMs;
            submittedTriangles=grid.submittedTriangles;submittedCorners=grid.submittedCorners;submittedBufferVertices=grid.submittedBufferVertices;draws=grid.draws;visibilityTests=grid.visibilityTests;culledCells=grid.culledCells;return;
        }
        long began=System.currentTimeMillis();
        for(int i=0;i<materials.length;i++){
            Material m=materials[i];if(m.source.drawmode!=m.mode)throw new IllegalStateException("BSP material edited");
            if(m.frame!=m.source.rImg){NativeImage found=null;for(int j=0;j<images.size();j++){NativeImage n=(NativeImage)images.elementAt(j);if(n.source==m.source.rImg){found=n;break;}}
                if(found==null)throw new IllegalStateException("BSP unprepared animation");m.appearance.setTexture(0,found.texture);m.appearance.getCompositingMode().setAlphaThreshold(found.threshold);m.frame=m.source.rImg;}
        }
        for(int i=0;i<16;i++)matrix[i]=0;matrix[0]=2.0f*focalX/width;matrix[5]=2.0f*focalY/height;matrix[10]=-(far+128.0f)/(far-128.0f);matrix[11]=-256.0f*far/(far-128.0f);matrix[14]=-1;
        float px=matrix[0],py=matrix[5],pz=matrix[10],pw=matrix[11];projection.set(matrix);camera.setGeneric(projection);
        float fp=Matrix.FP;matrix[0]=(pose.m00>>2)/(fp/4);matrix[1]=(pose.m01>>2)/(fp/4);matrix[2]=(pose.m02>>2)/(fp/4);matrix[3]=pose.m03;
        matrix[4]=(pose.m10>>2)/(fp/4);matrix[5]=(pose.m11>>2)/(fp/4);matrix[6]=(pose.m12>>2)/(fp/4);matrix[7]=pose.m13;
        matrix[8]=(pose.m20>>2)/(fp/4);matrix[9]=(pose.m21>>2)/(fp/4);matrix[10]=(pose.m22>>2)/(fp/4);matrix[11]=pose.m23;
        matrix[12]=matrix[13]=matrix[14]=0;matrix[15]=1;matrix[3]+=matrix[2]*focalY;matrix[7]+=matrix[6]*focalY;matrix[11]+=matrix[10]*focalY;
        view.set(matrix);submittedBufferVertices=draws=submittedTriangles=submittedCorners=visibilityTests=culledCells=0;
        if(culling)visibility.set(matrix,px,py,pz,pw);Selection selected=select();
        long prepared=System.currentTimeMillis();prepareMs=duration(began,prepared);graphics.bindTarget(target,mode==2,0);
        long bound=System.currentTimeMillis(),drawEnd=bound;bindMs=duration(prepared,bound);
        try {
            graphics.setViewport(0,0,width,height);graphics.setCamera(camera,view);graphics.resetLights();graphics.clear(background);
            long cleared=System.currentTimeMillis();clearMs=duration(bound,cleared);
            for(int r=0;r<selected.page.length;r++){
                Page page=pages[selected.page[r]];graphics.render(page.vertices,selected.indices[r],materials[page.material].appearance,null);draws++;submittedBufferVertices+=page.count;submittedCorners+=selected.counts[r];submittedTriangles+=selected.counts[r]/3;
            }
            drawEnd=System.currentTimeMillis();drawMs=duration(cleared,drawEnd);
        }finally{graphics.releaseTarget();releaseMs=duration(drawEnd,System.currentTimeMillis());}
    }
    private static int duration(long a,long b){return b>=a?(int)Math.min(Integer.MAX_VALUE,b-a):0;}
    public void dispose(){if(grid!=null)grid.dispose();images.removeAllElements();materials=null;pages=null;planes=null;children=spanRanges=bounds=spans=stack=order=null;cache=null;disposed=true;}
}
