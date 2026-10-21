package com;

import java.io.IOException;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;
import code.Math.Matrix;

/** Stock upstream indoor level (metro.3d2 + metro_1.bmp) loaded exactly like
 * Respawn.createScene, then drawn through the stock Renderer.addMesh(Node)
 * path with the benchmark camera/projection Adapter. World-only: no house
 * parts/portals/AI/skybox. NOT a rasterizer or a FPS meter. */
public final class MetroBenchmark {
    public static final int EYE_HEIGHT=2005; // upstream setCharacterSize(2005), camera = ground + height
    private final Mesh[] meshes;
    private final float[] projection=new float[16],camera=new float[16];
    private Renderer renderer;
    private int width,height;
    public final int triangles,vertices;
    public MetroBenchmark() throws IOException {
        Texture tex=Texture.createTexture("/l2d-metro/metro_1.bmp");
        if(tex==null || tex.img==null)throw new IOException("metro texture");
        MeshData[] data=MeshData.loadMeshes3D2("/l2d-metro/metro.3d2",tex.img,7500,false,true);
        if(data==null || data.length==0)throw new IOException("metro load failed");
        meshes=new Mesh[data.length];int t=0,v=0;
        for(int i=0;i<data.length;i++) {
            if(data[i]==null || data[i].getM3GMesh()==null)throw new IOException("metro mesh "+i);
            meshes[i]=data[i].getM3GMesh();
            t+=data[i].getQuadsCount()*2+data[i].getTrisCount();
            v+=data[i].getVerts().length/3;
        }
        triangles=t;vertices=v;
        // Same state normalization as the kordos L2D experiment: shared
        // PolygonMode/CompositingMode instances already sit on every submesh.
        PolygonMode pm=meshes[0].getAppearance(0).getPolygonMode();
        pm.setCulling(PolygonMode.CULL_BACK);pm.setWinding(PolygonMode.WINDING_CW);pm.setShading(PolygonMode.SHADE_SMOOTH);pm.setPerspectiveCorrectionEnable(true);
        CompositingMode cm=meshes[0].getAppearance(0).getCompositingMode();
        cm.setDepthTestEnable(true);cm.setDepthWriteEnable(true);cm.setBlending(CompositingMode.REPLACE);cm.setAlphaThreshold(0);
    }
    public int calls(){return meshes.length;}
    public void render(Graphics target,Matrix pose,int w,int h,int fx,int fy) {
        if(renderer==null || width!=w || height!=h){renderer=new Renderer(w,h);width=w;height=h;}
        for(int i=0;i<16;i++)projection[i]=0;
        projection[0]=2.0f*fx/w;projection[5]=2.0f*fy/h;projection[10]=-(60000f+128)/(60000f-128);projection[11]=-2f*128*60000/(60000-128);projection[14]=-1;
        float fp=Matrix.FP;
        // Same pitch shift convention as the kordos L2D adapter.
        camera[0]=(pose.m00>>2)/(fp/4);camera[1]=(pose.m01>>2)/(fp/4);camera[2]=(pose.m02>>2)/(fp/4);camera[3]=pose.m03;
        camera[4]=(pose.m10>>2)/(fp/4);camera[5]=(pose.m11>>2)/(fp/4);camera[6]=(pose.m12>>2)/(fp/4);camera[7]=pose.m13;
        camera[8]=(pose.m20>>2)/(fp/4);camera[9]=(pose.m21>>2)/(fp/4);camera[10]=(pose.m22>>2)/(fp/4);camera[11]=pose.m23;
        camera[3]+=camera[2]*fy;camera[7]+=camera[6]*fy;camera[11]+=camera[10]*fy;camera[12]=camera[13]=camera[14]=0;camera[15]=1;
        renderer.configureBenchmark(projection,camera);
        try {
            renderer.prepareRender(target,0,0);
            Graphics3D.getInstance().resetLights();
            renderer.setClip(0,0,w,h);
            for(int p=0;p<meshes.length;p++)renderer.addMesh(meshes[p],null,null);
        } finally {
            if(renderer.isBenchmarkBound())renderer.flush(target);
        }
    }
}
