package code.Gameplay.Map;

import code.AI.Player;
import code.Math.MathUtils;
import code.Math.Matrix;
import code.Rendering.MultyTexture;
import code.Rendering.DirectX7;
import code.Rendering.Meshes.SkyMesh;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Texture;
import code.Rendering.ScreenLines;
import code.Rendering.SkyFlash;
import code.Rendering.SolidSky;
import code.Rendering.PanoramaSky;
import code.utils.Asset;
import javax.microedition.lcdui.Graphics;

public class Skybox {

    private byte mode = 0;
	
    private Matrix matrix;
    private Mesh mesh;
    private SkyMesh nativeMesh;
    private ScreenLines lightningLines;
    private SkyFlash skyFlash;
    private SolidSky nativeSolidSky;
    private int nativeSolidColor;
    private PanoramaSky nativePanorama;
    public MultyTexture texture;
	
    private int color, groundColor;
    private Texture tex;
	private float repeatX, repeatY;
	private int lowestDegree;
	private float horizonScale;
	private float horizonOffset;
	
    public int x1;
    public int y1;
    public int x2;
    public int y2;
	
    private boolean resetViewport;
    public boolean skyboxAlways = false;
    public boolean draw = true;
	
    public Texture skyLighting;
    public boolean lighting = false;
    public long lastLighting = -1;
    
    public float rotateX, rotateY;
    
    public Skybox() {
    }

    public Skybox(String modelPath, String texturePath) {
        resetViewport = false;
        skyboxAlways = false;
        mode = 0;
		
        matrix = new Matrix();
		texture = new MultyTexture(texturePath, true);
        mesh = Room.loadMeshes(modelPath, 7.0F, 7.0F, 7.0F, texture)[0];
    }

    public Skybox(int colors) {
        resetViewport = false;
        skyboxAlways = false;
        mode = 1;
        color = colors;
    }

    public Skybox(Texture colors, int lowestDegree, float horizonScale, float horizonOffset) {
        resetViewport = false;
        skyboxAlways = false;
        mode = 3;
        tex = colors;
		
        this.lowestDegree = lowestDegree;
        this.horizonScale = horizonScale;
		this.horizonOffset = horizonOffset;
    }

    public Skybox(Texture colors, 
			float repeatX, float repeatY,
			int lowestDegree, 
			float horizonScale, float horizonOffset,
			int color, int groundColor) {
        resetViewport = false;
        skyboxAlways = false;
        mode = 2;
        tex = colors;
		
		this.repeatX = repeatX;
		this.repeatY = repeatY;
        this.lowestDegree = lowestDegree;
        this.horizonScale = horizonScale;
		this.horizonOffset = horizonOffset;
		
		this.color = color;
		this.groundColor = groundColor;
    }

    public void destroy() {
        matrix = null;
        texture = null;
        
        if (mesh != null) mesh.destroy();
        mesh = null;
        nativeMesh = null;
        lightningLines = null;
        skyFlash = null;
        nativeSolidSky = null;
        nativePanorama = null;

        tex = null;

    }

    public void resetViewport() {
        resetViewport = true;
        draw = false;
    }

    public void addViewport(int ax1, int ay1, int ax2, int ay2) {
        if (resetViewport) {
            resetViewport = false;
            x1 = ax1;
            y1 = ay1;
            x2 = ax2;
            y2 = ay2;
        } else {
            if (ax1 < x1) x1 = ax1;
            if (ay1 < y1) y1 = ay1;
            if (ax2 > x2) x2 = ax2;
            if (ay2 > y2) y2 = ay2;
        }
    }

	public final void render(Graphics g, DirectX7 g3d) {
		if(skyboxAlways) {
			x1 = 0;
			y1 = 0;
			x2 = g3d.width;
			y2 = g3d.height;
		}

		if(lighting) {
            // M3G-only: cheapest storm - replace the sky, no CPU bolts or glow.
            if(skyFlash == null) skyFlash = new SkyFlash();
            g3d.addRenderObjectDT(skyFlash,null);
            lighting = false;
            return;
		} else if(mode == 0) {
			matrix.setIdentity();
			matrix.setPosition(g3d.getCamera().m03, g3d.getCamera().m13, g3d.getCamera().m23);
			Matrix fm = g3d.computeFinalMatrix(matrix);
			
			mesh.getTexture().updateAnimation();
            if(nativeMesh == null) nativeMesh = new SkyMesh(mesh);
            nativeMesh.set(fm, x1, y1, x2, y2);
            g3d.addRenderObjectDT(nativeMesh, null);
			
		} else if(mode == 1) {
            renderSolid(g3d,color);
			
		} else if(mode == 2) {
			render2DSkybox(g3d);

		} else if(mode == 3) {
            {
                if(x2<=x1 || y2<=y1) return;
                renderSolid(g3d,tex.rImg.img[0]&0xffffff);
            }
		}
    }

    private void renderSolid(DirectX7 g3d,int rgb) {
        if(nativeSolidSky==null || nativeSolidColor!=rgb) {
            nativeSolidSky=new SolidSky(rgb);
            nativeSolidColor=rgb;
        }
        g3d.addRenderObjectDT(nativeSolidSky,null);
    }
    
void render2DSkybox(DirectX7 g3d) {
		if(x2 - x1 == 0 || y2 - y1 == 0) return;
			
		int beginx = (int) ((360 - rotateY - g3d.fovX / 2) * tex.rImg.w * repeatX / 360);
		int endx = (int) ((360 - rotateY + g3d.fovX / 2) * tex.rImg.w * repeatX / 360);
            
        int horizonY = (int) (g3d.height/2 + rotateX*g3d.height/g3d.fovY);
        horizonY = (horizonY-g3d.centreY) *g3d.distY /(11 + g3d.distY) + g3d.centreY;
		horizonY -= horizonOffset * g3d.height / g3d.fovY;
            
        int ySize = (int) (float) (90*horizonScale*g3d.height/g3d.fovY);

		int beginy = horizonY - ySize;
		int endy = horizonY + ySize;
		if(endy <= beginy) endy = beginy + 1;
        
        if(lowestDegree!=-91) {
            int horCrop = (int) (g3d.height/2 - lowestDegree*g3d.height/g3d.fovY  + rotateX*g3d.height/g3d.fovY);
            horCrop = (horCrop-g3d.centreY) *g3d.distY / (11 + g3d.distY) + g3d.centreY;
            if(y2>horCrop) y2=horCrop;
        }
		
        final int fp = 12;

        int sx = beginx << fp;

        final int width = g3d.width;

        final int xstep = ((endx << fp) - sx) / width;
        final int ystep = (int) ((tex.rImg.h << fp) * repeatY) / (endy - beginy);
        if(nativePanorama==null) nativePanorama=new PanoramaSky(this,tex,color,groundColor);
        nativePanorama.set(x1,y1,x2,y2,sx,beginy,endy,xstep,ystep);
        g3d.addRenderObjectDT(nativePanorama,null);
    }

public Mesh getMesh() {
        return mesh;
    }
    
private void lightningLine(DirectX7 g,boolean nativeLines,int x1,int y1,int x2,int y2,int fat,int color) {
        if(nativeLines) lightningLines.line(x1,y1,x2,y2,fat,color,true);
        else g.drawLine(x1,y1,x2,y2,fat,color);
    }
}
