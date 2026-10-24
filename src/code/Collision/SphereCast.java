package code.Collision;

import code.Math.FastMath;
import code.Math.MathUtils2;
import code.Math.Matrix;
import code.Rendering.DirectX7;
import code.Rendering.Meshes.Mesh;
import code.Rendering.Meshes.Polygon3V;
import code.Rendering.RenderObject;
import code.Rendering.Vertex;
import code.Math.Vector3D;
import code.Rendering.Meshes.Polygon4V;
import code.Rendering.Texture;

public final class SphereCast {

    private static final Vector3D temp = new Vector3D();
    private static final Vector3D nor = new Vector3D();
    private static final Vector3D v1 = new Vector3D(),
                                  v2 = new Vector3D(),
                                  v3 = new Vector3D(),
                                  v4 = new Vector3D();

    public static boolean isSphereAABBCollision(Vector3D pos, int rad, int minx, int maxx, int minz, int maxz) {
        return !(pos.x+rad < minx || pos.z+rad < minz || pos.x-rad > maxx || pos.z-rad > maxz);
    }
    

    public static boolean sphereCast(Mesh mesh, Matrix matrix, Vector3D pos, int rad) {
        DirectX7.transformSave(mesh,matrix);
        return sphereCast(mesh,pos,rad,true);
    }
    
    public static boolean sphereCast(Mesh mesh, Vector3D pos, int rad) {
        return sphereCast(mesh,pos,rad,false);
    }
    
    public static boolean sphereCast(Mesh mesh, Vector3D pos, int rad, boolean recalc) {
        RenderObject[] elements = mesh.getPolygons();
        boolean col = false;
        // ARM9: для статического запроса (recalc==false - геометрия комнат,
        // вершины после загрузки не меняются) AABB полигонов берётся из кэша
        // Mesh вместо пересчёта min/max по вершинам на каждый вызов (физика
        // вызывает это на каждого персонажа каждый кадр). Для движущихся мешей
        // (recalc==true, вершины только что трансформированы transformSave,
        // который кэш сбросил) остался прежний пересчёт по месту.
        final int[] bb = recalc ? null : mesh.getAABB();
        for(int i=0; i<elements.length; i++) {
            RenderObject poly = elements[i];
            int dis = Integer.MAX_VALUE;
            int mat;

            if(poly.kind == RenderObject.KIND_POLY4) {
                Polygon4V p = (Polygon4V) poly;
                mat = p.tex;

                // ARM9: фильтр текстур поднят до AABB и расчёта расстояния:
                // неколлизионные полигоны раньше считали всё целиком, а потом
                // результат выбрасывался проверкой в конце цикла. Поведение
                // идентично: оба фильтра - независимые continue.
                if(mat != -1) {
                    Texture tex = mesh.getTexture().textures[mat];
                    if(!tex.collision) continue;
                }

                final Vertex a = p.a;
                final Vertex b = p.b;
                final Vertex c = p.c;
                final Vertex d = p.d;

                int minx, maxx, miny, maxy, minz, maxz;
                if(bb != null) {
                    final int o = i * 6;
                    minx = bb[o]; miny = bb[o + 1]; minz = bb[o + 2];
                    maxx = bb[o + 3]; maxy = bb[o + 4]; maxz = bb[o + 5];
                } else {
                    minx = maxx = a.x;
                    if(b.x > maxx) maxx = b.x;
                    if(c.x > maxx) maxx = c.x;
                    if(d.x > maxx) maxx = d.x;
                    if(b.x < minx) minx = b.x;
                    if(c.x < minx) minx = c.x;
                    if(d.x < minx) minx = d.x;
                    miny = maxy = a.y;
                    if(b.y > maxy) maxy = b.y;
                    if(c.y > maxy) maxy = c.y;
                    if(d.y > maxy) maxy = d.y;
                    if(b.y < miny) miny = b.y;
                    if(c.y < miny) miny = c.y;
                    if(d.y < miny) miny = d.y;
                    minz = maxz = a.z;
                    if(b.z > maxz) maxz = b.z;
                    if(c.z > maxz) maxz = c.z;
                    if(d.z > maxz) maxz = d.z;
                    if(b.z < minz) minz = b.z;
                    if(c.z < minz) minz = c.z;
                    if(d.z < minz) minz = d.z;
                }
                if(maxx < pos.x-rad) continue;
                if(minx > pos.x+rad) continue;
                if(maxz < pos.z-rad) continue;
                if(minz > pos.z+rad) continue;
                if(maxy < pos.y-rad) continue;
                if(miny > pos.y+rad) continue;

                v1.x=a.x; v1.y=a.y; v1.z=a.z;
                v2.x=b.x; v2.y=b.y; v2.z=b.z;
                v3.x=c.x; v3.y=c.y; v3.z=c.z;
                v4.x=d.x; v4.y=d.y; v4.z=d.z;
                nor.x=p.nx;
                nor.y=p.ny;
                nor.z=p.nz;
                if(recalc) MathUtils2.calcNormalFast(nor, v1, v2, v4);
                dis = distanceSphereToPolygon(v1, v2, v3, v4, nor, pos, rad);
            } else if(poly.kind == RenderObject.KIND_POLY3) {
                Polygon3V p = (Polygon3V) poly;
                mat = p.tex;

                if(mat != -1) {
                    Texture tex = mesh.getTexture().textures[mat];
                    if(!tex.collision) continue;
                }

                final Vertex a = p.a;
                final Vertex b = p.b;
                final Vertex c = p.c;

                int minx, maxx, miny, maxy, minz, maxz;
                if(bb != null) {
                    final int o = i * 6;
                    minx = bb[o]; miny = bb[o + 1]; minz = bb[o + 2];
                    maxx = bb[o + 3]; maxy = bb[o + 4]; maxz = bb[o + 5];
                } else {
                    minx = maxx = a.x;
                    if(b.x > maxx) maxx = b.x;
                    if(c.x > maxx) maxx = c.x;
                    if(b.x < minx) minx = b.x;
                    if(c.x < minx) minx = c.x;
                    miny = maxy = a.y;
                    if(b.y > maxy) maxy = b.y;
                    if(c.y > maxy) maxy = c.y;
                    if(b.y < miny) miny = b.y;
                    if(c.y < miny) miny = c.y;
                    minz = maxz = a.z;
                    if(b.z > maxz) maxz = b.z;
                    if(c.z > maxz) maxz = c.z;
                    if(b.z < minz) minz = b.z;
                    if(c.z < minz) minz = c.z;
                }
                if(maxx < pos.x-rad) continue;
                if(minx > pos.x+rad) continue;
                if(maxz < pos.z-rad) continue;
                if(minz > pos.z+rad) continue;
                if(maxy < pos.y-rad) continue;
                if(miny > pos.y+rad) continue;

                v1.x=a.x; v1.y=a.y; v1.z=a.z;
                v2.x=b.x; v2.y=b.y; v2.z=b.z;
                v3.x=c.x; v3.y=c.y; v3.z=c.z;
                nor.x=p.nx;
                nor.y=p.ny;
                nor.z=p.nz;
                if(recalc) MathUtils2.calcNormalFast(nor, v1, v2, v3);
                dis = distanceSphereToPolygon(v1, v2, v3, nor, pos, rad);
            } else continue;   // mat==-1, dis==MAX - полигон и раньше был no-op

            if(dis != Integer.MAX_VALUE && dis > 0) {
                pos.add(-nor.x*dis>>12, -nor.y*dis>>12, -nor.z*dis>>12);
                col = true;
            }
        }
        return col;
    }
    
    /**
     * Дешёвая отсечка ребра: если точка дальше rad от AABB ребра хотя бы по
     * одной оси, расстояние до ребра заведомо больше rad.
     */
    private static boolean edgeFar(Vector3D p, Vector3D a, Vector3D b, int rad) {
        int min = a.x < b.x ? a.x : b.x;
        int max = a.x > b.x ? a.x : b.x;
        int d = p.x < min ? min - p.x : (p.x > max ? p.x - max : 0);
        if(d > rad) return true;

        min = a.y < b.y ? a.y : b.y;
        max = a.y > b.y ? a.y : b.y;
        d = p.y < min ? min - p.y : (p.y > max ? p.y - max : 0);
        if(d > rad) return true;

        min = a.z < b.z ? a.z : b.z;
        max = a.z > b.z ? a.z : b.z;
        d = p.z < min ? min - p.z : (p.z > max ? p.z - max : 0);
        return d > rad;
    }

    private static int distanceSphereToPolygon(Vector3D a, Vector3D b, Vector3D c, Vector3D nor,
            Vector3D point, int rad) {
        temp.x=point.x-a.x;
        temp.y=point.y-a.y;
        temp.z=point.z-a.z;
        int dot = temp.dot(nor)>>12; //расстояние до плоскости
        if(dot > rad) return Integer.MAX_VALUE;
        //проекция на плоскость
        temp.x=point.x-(nor.x*dot>>12);
        temp.y=point.y-(nor.y*dot>>12);
        temp.z=point.z-(nor.z*dot>>12);
        if(MathUtils2.isPointOnPolygon(temp, a, b, c, nor)) {
            int dis = dot;
            if(dot<0) dis = -dot;
            dis = rad - dis;
            return dis;
        }

        // Ребро, которое заведомо дальше rad, не может дать min <= rad*rad,
        // поэтому дорогое distanceToLine() (внутри 64-битное деление) для него
        // не вызывается вовсе.
        final int len1 = edgeFar(point, a, b, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, a, b);
        final int len2 = edgeFar(point, b, c, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, b, c);
        final int len3 = edgeFar(point, c, a, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, c, a);

        int min = len1;
        if(len2 < min) min = len2;
        if(len3 < min) min = len3;
        // FastMath.isqrt точнее, чем (int)(1/invSqrt(x)) (у того ~1-2% ошибки),
        // и не требует float-деления 1/x, которое на ARM9 очень дорогое.
        if(min <= rad*rad) return rad - FastMath.isqrt(min);
        return Integer.MAX_VALUE;
    }

    private static int distanceSphereToPolygon(Vector3D a, Vector3D b, Vector3D c, Vector3D d, Vector3D nor,
            Vector3D point, int rad) {
        temp.x=point.x-a.x;
        temp.y=point.y-a.y;
        temp.z=point.z-a.z;
        int dot = temp.dot(nor)>>12;//расстояние до плоскости
        if(dot > rad) return Integer.MAX_VALUE;
        //проекция на плоскость
        temp.x=point.x-(nor.x*dot>>12);
        temp.y=point.y-(nor.y*dot>>12);
        temp.z=point.z-(nor.z*dot>>12);
        if(MathUtils2.isPointOnPolygon(temp, a, b, c, d, nor)) {
            int dis = dot;
            if(dot<0) dis = -dot;
            dis = rad - dis;
            return dis;
        }

        final int len1 = edgeFar(point, a, b, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, a, b);
        final int len2 = edgeFar(point, b, c, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, b, c);
        final int len3 = edgeFar(point, c, d, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, c, d);
        final int len4 = edgeFar(point, d, a, rad) ? Integer.MAX_VALUE : MathUtils2.distanceToLine(point, d, a);

        int min = len1;
        if(len2 < min) min = len2;
        if(len3 < min) min = len3;
        if(len4 < min) min = len4;
        if(min <= rad*rad) return rad - FastMath.isqrt(min);
        return Integer.MAX_VALUE;
    }


}

