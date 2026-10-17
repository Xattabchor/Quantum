package code.Rendering;

import code.Collision.Ray;
import code.Gameplay.Map.House;
import code.Math.Matrix;
import code.Rendering.Meshes.Polygon4V;

/** Cosmetic screen-space flash, not a light source or a surface decal. */
public final class MuzzleGlow extends RenderObject {
    static final int DURATION = 120, RANGE = 5000, SIZE = 32;
    final Polygon4V quad = new Polygon4V(new Vertex(-1,1,-1),new Vertex(1,1,-1),
            new Vertex(1,-1,-1),new Vertex(-1,-1,-1),
            (byte)0,(byte)0,(byte)255,(byte)0,(byte)255,(byte)255,(byte)0,(byte)255);
    Texture texture;
    int alpha;
    private long started;
    private boolean active, cached;
    private final Ray ray = new Ray();
    private House cachedHouse;
    private int part, x,y,z,dx,dy,dz,distance;

    void start(long now) { started=now;active=true;cached=false;alpha=0; }

    boolean prepare(DirectX7 g,House house,int room,long now) {
        alpha=0;
        long age=now-started;
        if(!active || age<0 || age>=DURATION) {
            active=false;cachedHouse=null;return false;
        }
        if(house==null || room<0 || house.getRooms()==null || room>=house.getRooms().length
                || house.getRooms()[room]==null) return false;
        Matrix c=g.camera;
        if(c.m02==0 && c.m12==0 && c.m22==0) return false;
        if(!cached || cachedHouse!=house || part!=room || x!=c.m03 || y!=c.m13 || z!=c.m23
                || dx!=c.m02 || dy!=c.m12 || dz!=c.m22) {
            cached=true;cachedHouse=house;part=room;
            x=c.m03;y=c.m13;z=c.m23;dx=c.m02;dy=c.m12;dz=c.m22;
            ray.reset();ray.findNearest=true;ray.doubleSide=false;ray.infinity=false;
            ray.onlyCollidable=true;ray.ignoreNonShadowed=false;
            ray.getStart().set(x,y,z);ray.getDir().set(-dx,-dy,-dz);ray.getDir().setLength(RANGE);
            house.rayCastScreenEffect(room,ray);
            distance=ray.isCollision()?ray.getDistance():RANGE;
        }
        if(distance<0 || distance>=RANGE) return false;
        int timeAlpha=(int)((DURATION-age)*200/DURATION);
        alpha=timeAlpha*timeAlpha/255*(RANGE-distance)/RANGE;
        if(alpha<=0) return false;
        if(texture==null) texture=createTexture();
        int radius=Math.max(2,Math.min(g.width,g.height)*(2*RANGE-distance)/(5*RANGE));
        int cx=g.centreX,cy=g.centreY+g.height/10;
        set(quad.a,cx-radius,cy-radius);set(quad.b,cx+radius,cy-radius);
        set(quad.c,cx+radius,cy+radius);set(quad.d,cx-radius,cy+radius);
        return true;
    }

    private static void set(Vertex v,int x,int y) { v.sx=x;v.sy=y;v.rz=-1; }

    private static Texture createTexture() {
        int[] pixels=new int[SIZE*SIZE];
        for(int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
            float dx=(2.0f*x-(SIZE-1))/(SIZE-1),dy=(2.0f*y-(SIZE-1))/(SIZE-1);
            float edge=Math.max(0,1-dx*dx-dy*dy);
            int a=(int)(220*edge*edge);
            pixels[y*SIZE+x]=a==0?0:(a<<24)|0xffd080;
        }
        Texture result=new Texture(new RawImage(pixels,SIZE,SIZE),false,null);
        result.drawmode=4;return result;
    }

    public boolean isVisible(int x1,int y1,int x2,int y2) { return alpha>0; }
    // A failed cosmetic overlay is skipped; Software retains its old lighting.
    public void render(DirectX7 g,Texture t) { }
    public void renderFast(DirectX7 g,Texture t) { }
}
