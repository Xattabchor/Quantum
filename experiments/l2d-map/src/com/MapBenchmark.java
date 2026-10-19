package com;

import java.io.DataInputStream;
import java.io.IOException;
import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;
import code.Math.Matrix;

/** Import adapter only; stock MeshData3D2 loading and Renderer.addMesh(Node) draw path. */
public final class MapBenchmark {
    private final Mesh[] meshes;
    private final Appearance[] appearances;
    private final Texture2D[][] textures;
    private final float[][] thresholds;
    private final boolean[] river;
    private final int[] currentFrames;
    private final float[] projection=new float[16],camera=new float[16];
    private Renderer renderer;
    private int width,height;
    public final int triangles,vertices;
    public MapBenchmark() throws IOException {
        DataInputStream in=new DataInputStream(MapBenchmark.class.getResourceAsStream("/l2d-map/map.bin"));
        try {
            if(in.readInt()!=0x4c32424d)throw new IOException("map manifest");
            int n=in.readUnsignedShort();triangles=in.readInt();vertices=in.readInt();
            currentFrames=new int[n];meshes=new Mesh[n];appearances=new Appearance[n];textures=new Texture2D[n][];thresholds=new float[n][];river=new boolean[n];
            for(int p=0;p<n;p++) {
                String file=in.readUTF();river[p]=in.readBoolean();int count=in.readUnsignedShort(),faces=in.readUnsignedShort(),frames=in.readUnsignedByte();
                int[] strips=new int[faces];int corners=0;
                for(int f=0;f<faces;f++){strips[f]=in.readUnsignedByte();corners+=strips[f];}
                int[] indices=new int[corners];for(int i=0;i<corners;i++){indices[i]=in.readUnsignedShort();if(indices[i]>=count)throw new IOException("map index");}
                textures[p]=new Texture2D[frames];thresholds[p]=new float[frames];Image2D first=null;
                for(int f=0;f<frames;f++) {
                    String image=in.readUTF();int w=in.readUnsignedShort(),h=in.readUnsignedShort();thresholds[p][f]=in.readFloat();
                    byte[] data=new byte[w*h*4];DataInputStream raw=new DataInputStream(MapBenchmark.class.getResourceAsStream(image));
                    try{raw.readFully(data);if(raw.read()!=-1)throw new IOException("RGBA length");}finally{raw.close();}
                    Image2D pixels=new Image2D(Image2D.RGBA,w,h,data);if(f==0)first=pixels;
                    Texture2D t=new Texture2D(pixels);t.setFiltering(Texture2D.FILTER_BASE_LEVEL,Texture2D.FILTER_NEAREST);t.setWrapping(Texture2D.WRAP_REPEAT,Texture2D.WRAP_REPEAT);t.setBlending(Texture2D.FUNC_MODULATE);t.setBlendColor(0);textures[p][f]=t;
                }
                MeshData[] loaded=MeshData.loadMeshes3D2(file,first,6,false,true);
                if(loaded==null || loaded.length!=1 || loaded[0]==null)throw new IOException("L2D3D2 load failed");
                Mesh original=loaded[0].getM3GMesh();Appearance a=original.getAppearance(0);
                // Stock3D2 groups quads before tris. Restore source order without
                // changing the vertex attributes, winding or quad diagonal.
                meshes[p]=new Mesh(original.getVertexBuffer(),new TriangleStripArray(indices,strips),a);appearances[p]=a;
                PolygonMode pm=a.getPolygonMode();pm.setCulling(PolygonMode.CULL_BACK);pm.setWinding(PolygonMode.WINDING_CW);pm.setShading(PolygonMode.SHADE_SMOOTH);pm.setPerspectiveCorrectionEnable(true);
                CompositingMode cm=new CompositingMode();cm.setDepthTestEnable(true);cm.setDepthWriteEnable(!river[p]);cm.setBlending(river[p]?CompositingMode.MODULATE:CompositingMode.REPLACE);cm.setAlphaThreshold(thresholds[p][0]);a.setCompositingMode(cm);a.setTexture(0,textures[p][0]);a.setFog(null);a.setMaterial(null);
            }
            if(in.read()!=-1)throw new IOException("manifest trailing bytes");
        } finally {in.close();}
    }
    public int calls(){return meshes.length;}
    public void render(Graphics target,Matrix pose,int w,int h,int fx,int fy,int frame) {
        if(renderer==null || width!=w || height!=h){renderer=new Renderer(w,h);width=w;height=h;}
        for(int p=0;p<meshes.length;p++){int f=river[p]?frame:0;if(currentFrames[p]!=f){appearances[p].setTexture(0,textures[p][f]);appearances[p].getCompositingMode().setAlphaThreshold(thresholds[p][f]);currentFrames[p]=f;}}
        for(int i=0;i<16;i++)projection[i]=0;
        projection[0]=2.0f*fx/w;projection[5]=2.0f*fy/h;projection[10]=-(60000f+128)/(60000f-128);projection[11]=-2f*128*60000/(60000-128);projection[14]=-1;
        float fp=Matrix.FP;
        camera[0]=(pose.m00>>2)/(fp/4);camera[1]=(pose.m01>>2)/(fp/4);camera[2]=(pose.m02>>2)/(fp/4);camera[3]=pose.m03;
        camera[4]=(pose.m10>>2)/(fp/4);camera[5]=(pose.m11>>2)/(fp/4);camera[6]=(pose.m12>>2)/(fp/4);camera[7]=pose.m13;
        camera[8]=(pose.m20>>2)/(fp/4);camera[9]=(pose.m21>>2)/(fp/4);camera[10]=(pose.m22>>2)/(fp/4);camera[11]=pose.m23;
        camera[3]+=camera[2]*fy;camera[7]+=camera[6]*fy;camera[11]+=camera[10]*fy;camera[12]=camera[13]=camera[14]=0;camera[15]=1;
        renderer.configureBenchmark(projection,camera);
        try{renderer.prepareRender(target,0,0);Graphics3D.getInstance().resetLights();renderer.setClip(0,0,w,h);for(int p=0;p<meshes.length;p++)renderer.addMesh(meshes[p],null,null);}
        finally{if(renderer.isBenchmarkBound())renderer.flush(target);}
    }
}
