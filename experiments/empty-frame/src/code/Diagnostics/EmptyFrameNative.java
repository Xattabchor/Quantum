package code.Diagnostics;

import javax.microedition.lcdui.Graphics;
import javax.microedition.m3g.*;

/** No scene, meshes, textures, lights or render calls. Lazily created once.
 * Pure MIDP modes do not instantiate or call this helper.
 */
final class EmptyFrameNative {
    private final Graphics3D api=Graphics3D.getInstance();
    private final Camera camera=new Camera();
    private final Transform view=new Transform(),projection=new Transform();
    private final Background background=new Background();
    private final float[] matrix=new float[16];
    EmptyFrameNative(){
        matrix[0]=matrix[5]=1;matrix[10]=-40128f/39872;matrix[11]=-10240000f/39872;matrix[14]=-1;
        projection.set(matrix);camera.setGeneric(projection);background.setColor(0x203040);
    }
    void frame(Graphics target,int width,int height,int mode,long[] ops){
        boolean depth=mode!=6,clear=mode!=3;
        long t=System.currentTimeMillis();api.bindTarget(target,depth,0);long bound=System.currentTimeMillis();ops[EmptyFrameStats.B]=bound-t;
        try{
            api.setViewport(0,0,width,height);api.setCamera(camera,view);api.resetLights();
            background.setColorClearEnable(clear);background.setDepthClearEnable(mode==5 || mode==7);
            long configured=System.currentTimeMillis();ops[EmptyFrameStats.S]=configured-bound;
            if(clear){api.clear(background);ops[EmptyFrameStats.K]=System.currentTimeMillis()-configured;}
        }finally{
            long release=System.currentTimeMillis();try{api.releaseTarget();}finally{ops[EmptyFrameStats.E]=System.currentTimeMillis()-release;}
        }
    }
}
