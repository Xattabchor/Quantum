package code.AI;

import code.AI.misc.Blood;
import code.Gameplay.Map.*;
import code.Gameplay.Objects.GameObject;
import code.Gameplay.Shop;
import code.Math.*;
import code.Rendering.*;
import code.Rendering.Meshes.*;
import code.utils.Main;
import code.utils.QFPS;
import java.lang.reflect.*;
import java.util.*;

/** Behavioral references and deterministic work counts; no phone timing claims. */
public final class AITest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static final class CountingVector extends Vector {
        public synchronized Object elementAt(int i){AIStats.reads++;return super.elementAt(i);}
    }
    private static final class Probe extends Bot {
        Probe(int fraction){this.fraction=fraction;setHp(1000);hasBlood=false;character.transform.setIdentity();}
        protected void action(Scene scene){}
        public void render(DirectX7 g,int a,int b,int c,int d){}
        GameObject find(Vector v,GameObject ignore,int[] fractions){return findBot(v,ignore,fractions);}
    }
    private static final class Neutral extends GameObject {
        Neutral(){setHp(1000);}
        public void render(DirectX7 g,int a,int b,int c,int d){}
    }
    private static final Sprite[] ANIMS=new Sprite[5];
    private static final Method TARGETS;
    static {
        try {TARGETS=NPC.class.getDeclaredMethod("findTargets",Vector.class);TARGETS.setAccessible(true);}
        catch(Exception ex){throw new RuntimeException(ex);}
    }
    private static Object get(Object o,String name)throws Exception{
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void put(Object o,String name,Object value)throws Exception{
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static void select(NPC npc,Vector v)throws Exception{TARGETS.invoke(npc,v);}
    private static int[] fractions(Random r){
        int n=r.nextInt(6);if(n==0)return null;int[] list=new int[n-1];
        for(int i=0;i<list.length;i++)list[i]=r.nextInt(9)-2;return list;
    }
    private static void fov() {
        Random r=new Random(220);Probe observer=new Probe(1),target=new Probe(2);
        int[] edges={Integer.MIN_VALUE,Integer.MAX_VALUE,-30001,-30000,-4096,-2500,-1,0,1,2499,2500,4096,30000,30001};
        for(int i=0;i<250000;i++) {
            Matrix a=observer.character.transform,b=target.character.transform;
            a.m02=i%7==0?edges[r.nextInt(edges.length)]:r.nextInt(65537)-32768;
            a.m22=i%7==0?edges[r.nextInt(edges.length)]:r.nextInt(65537)-32768;
            if(i%17==0){a.m02=r.nextInt();a.m22=r.nextInt();}
            int range=i%3==0?10000:300000;
            observer.setPos(r.nextInt(range)-range/2,r.nextInt(range)-range/2,r.nextInt(range)-range/2);
            target.setPos(r.nextInt(range)-range/2,r.nextInt(range)-range/2,r.nextInt(range)-range/2);
            if(i%13==0){a.m03=r.nextInt();a.m13=r.nextInt();a.m23=r.nextInt();b.m03=r.nextInt();b.m13=r.nextInt();b.m23=r.nextInt();}
            check(Bot.isTargetInFOV(observer,target)==AIReference.fov(observer,target),"FOV legacy boundary/overflow differs at "+i);
        }
        observer.setPos(0,0,0);
        for(int x=-4096;x<=4096;x+=3)for(int delta=-3;delta<=3;delta++) {
            observer.character.transform.m02=x;observer.character.transform.m22=4096;
            target.setPos(10000,0,-x*10000/4096+delta);
            check(Bot.isTargetInFOV(observer,target)==AIReference.fov(observer,target),"perpendicular certification boundary changed");
        }
        int[][] overflowCases={{4096,0,Integer.MAX_VALUE,10},{0,4096,Integer.MIN_VALUE,4096},
                {Integer.MIN_VALUE,4096,4096,0},{4096,0,Integer.MIN_VALUE,0}};
        for(int[] c:overflowCases) {
            observer.character.transform.m02=c[0];observer.character.transform.m22=c[1];target.setPos(c[2],0,c[3]);
            check(Bot.isTargetInFOV(observer,target)==AIReference.fov(observer,target),"legacy normalization/dot overflow shortcut changed");
        }
        observer.setPos(0,0,0);observer.character.transform.m02=1;observer.character.transform.m22=4096;
        target.setPos(3000,0,0);check(!Bot.isTargetInFOV(observer,target),"raw-dot shortcut changed quantized FOV boundary");
        observer.character.transform.m02=0;target.setPos(0,0,-2499);check(Bot.isTargetInFOV(observer,target),"near rear target lost");
        target.setPos(0,0,-2500);check(!Bot.isTargetInFOV(observer,target),"2500 radius boundary widened");
        target.setPos(0,2400,-1000);check(!Bot.isTargetInFOV(observer,target),"3D near distance replaced with horizontal");
        AIStats.reset();for(int i=0;i<10000;i++)Bot.isTargetInFOV(observer,target);
        check(AIStats.vectors==0,"FOV still allocates vectors per query");
        System.out.println("PASS 250000 exact legacy FOV cases / 19117 perpendicular edge probes / zero+overflow+int truncation / 2500 radius / vertical distance / zero per-query Vector3D allocations");
    }
    private static void selectors()throws Exception {
        Random r=new Random(1710);NPC npc=new NPC(new Vector3D(),null,100,null);Probe probe=new Probe(1);
        Player player=new Player(32,32,new Vector3D(),null,null);GameObject[] pool=new GameObject[30];
        pool[0]=npc;pool[1]=probe;pool[2]=player;pool[3]=new Neutral();
        for(int i=4;i<pool.length;i++)pool[i]=new Probe(i%7-1);
        for(int trial=0;trial<16000;trial++) {
            npc.visiblityCheck=probe.visiblityCheck=(trial&1)!=0;
            int x=r.nextInt(10000),z=r.nextInt(10000);npc.setPos(x,0,z);probe.setPos(x,0,z);
            int lx=trial%19==0?r.nextInt():r.nextInt(32769)-16384,lz=trial%19==0?r.nextInt():r.nextInt(32769)-16384;
            npc.character.transform.m02=probe.character.transform.m02=lx;npc.character.transform.m22=probe.character.transform.m22=lz;
            Vector v=new Vector();v.addElement("non-object");v.addElement(null);
            for(int i=2;i<pool.length;i++) {
                GameObject o=pool[i];o.setHp(r.nextInt(5)==0?0:100);
                o.setPos(r.nextInt(60000)-30000,r.nextInt(8000)-4000,r.nextInt(60000)-30000);
                if(trial%23==0)o.setPos(r.nextInt(),r.nextInt(),r.nextInt());
            }
            // Identical positions exercise first-in-vector tie winners.
            pool[5].setPos(pool[4].getPosX(),pool[4].getPosY(),pool[4].getPosZ());
            for(int i=0;i<35;i++)v.addElement(pool[r.nextInt(pool.length)]);
            npc.toAttack=fractions(r);npc.toFollow=fractions(r);
            GameObject enemy=AIReference.findBot(npc,v,npc,npc.toAttack),follower=AIReference.findBot(npc,v,npc,npc.toFollow);
            select(npc,v);check(get(npc,"enemy")==enemy && get(npc,"follower")==follower,"combined roles differ at "+trial);
            GameObject ignore=pool[r.nextInt(pool.length)];int[] mask=fractions(r);
            check(probe.find(v,ignore,mask)==AIReference.findBot(probe,v,ignore,mask),"Bot/Zombie target selector differs");
        }
        System.out.println("PASS 16000 dual-role + Bot selectors / first ties / ignore/self / Player fraction0 / dead+non-actors / null+empty+overlapping factions / full-int positions");
    }
    private static Room room(int id,boolean wall) {
        Vertex a=new Vertex(-20000,0,-20000),b=new Vertex(20000,0,-20000),c=new Vertex(20000,0,20000),d=new Vertex(-20000,0,20000);
        Polygon4V floor=new Polygon4V(a,b,c,d,(byte)0,(byte)0,(byte)1,(byte)0,(byte)1,(byte)1,(byte)0,(byte)1);
        Vertex e=new Vertex(-20000,0,1500),f=new Vertex(-20000,10000,1500),g=new Vertex(20000,10000,1500),h=new Vertex(20000,0,1500);
        Polygon4V barrier=new Polygon4V(e,h,g,f,(byte)0,(byte)0,(byte)1,(byte)0,(byte)1,(byte)1,(byte)0,(byte)1);
        MultyTexture mt=new MultyTexture(new String[0]);mt.textures=new Texture[]{Blood.blood};
        Room result=new Room(new Mesh(new Vertex[]{a,b,c,d,e,f,g,h},wall?new RenderObject[]{floor,barrier}:new RenderObject[]{floor},mt),id);
        result.setPortals(new Portal[0]);return result;
    }
    private static final class Fixture {
        final Bot actor;final Scene scene;final boolean block;final GameObject[] targets=new GameObject[8];
        Fixture(boolean legacy,int seed)throws Exception {
            block=seed%3==0;
            actor=legacy?new LegacyNPC(new Vector3D(),null,500,null):new NPC(new Vector3D(),null,500,null);
            String[] animNames={"stayFront","walkFront","attackFront","damageFront","deathFront"};
            for(int i=0;i<animNames.length;i++)put(actor,animNames[i],ANIMS[i]);
            actor.getClass().getMethod("initStaySprite").invoke(actor);
            actor.hasBlood=false;actor.setCharacterSize(400,1503);actor.character.setUpdatable(false);actor.character.setOnFloor(true);
            actor.setPart(0);actor.visiblityCheck=seed%2==0;actor.fraction=1;
            Room[] rooms={room(0,seed%3==0),room(1,false)};
            Portal portal=new Portal(new Vertex[]{new Vertex(-500,0,1000),new Vertex(-500,3000,1000),new Vertex(500,3000,1000),new Vertex(500,0,1000)});
            portal.setRoom(rooms[1]);rooms[0].setPortals(new Portal[]{portal});
            House house=new House(rooms,new Room[][]{{rooms[1]},{rooms[0]}});
            scene=new Scene(32,32,house,null,null,new code.Gameplay.Respawn[0],0,0,null,1,false);
            if(block) {
                code.Collision.Ray test=new code.Collision.Ray();test.getStart().set(0,1503,0);test.getDir().set(0,0,6000);
                house.rayCast(0,test,false);
                RenderObject wall=rooms[0].fullMesh.getPolygons()[1];
                check(test.isCollision(),"wall fixture not blocking: normal="+wall.nx+","+wall.ny+","+wall.nz);
            }
            rooms[0].addObject(actor);
            for(int i=0;i<targets.length;i++) {
                GameObject t=i==0?new Player(32,32,new Vector3D(),null,null):i==1?new NPC(new Vector3D(),null,100,null):new Probe(i%4);
                if(t instanceof Bot)((Bot)t).hasBlood=false;
                t.setHp(100);t.character.setUpdatable(false);t.setPart(i==1 && seed%4==0?0:i%2);t.setCharacterSize(400,1503);
                if(t instanceof NPC){NPC n=(NPC)t;n.fragsOnDeath=3;n.moneyOnDeath=17;n.onDeath=new String[]{"test-death-script"};}
                t.setPos((i%3-1)*700,0,seed%4==0?200+i*50:3000+i*800);
                targets[i]=t;rooms[i%2].addObject(t);
            }
            put(actor,"toAttack",seed%7==0?null:new int[]{0,2,3});put(actor,"toFollow",seed%5==0?new int[]{0}:new int[]{-1});
            put(actor,"reacttimer",new Integer(new int[]{3,5,8}[seed%3]));put(actor,"attacktimer",new Integer(new int[]{1,3,8}[seed%3]));
            put(actor,"maxEnemyDistance",new Long(seed%3==0?4000:-1));put(actor,"damageSleepTime",new Integer(220));
            put(actor,"inPlayerTeam",Boolean.TRUE);put(actor,"unicalEnemies",new Vector());
            put(actor,"whenEnemyIsNear",new Integer(seed%3==0?1:2));put(actor,"whenEnemyIsFar",new Integer(seed%3==1?-1:1));
        }
        int id(Object o){if(o==null)return -1;if(o==actor)return -2;for(int i=0;i<targets.length;i++)if(o==targets[i])return i;return -3;}
        String state()throws Exception {
            Matrix m=actor.character.transform;Vector3D v=actor.character.speed;
            String s=id(get(actor,"enemy"))+":"+id(get(actor,"follower"))+":"+get(actor,"state")+":"+get(actor,"walkingToEnemy")
                    +":"+get(actor,"lastDamage")+":"+actor.getHp()+":"+actor.getFrameInter()+":"+m.m00+":"+m.m02+":"+m.m11+":"+m.m20+":"+m.m22+":"+v.x+":"+v.y+":"+v.z;
            int[] matrix={m.m00,m.m01,m.m02,m.m03,m.m10,m.m11,m.m12,m.m13,m.m20,m.m21,m.m22,m.m23};
            s+=":"+Arrays.toString(matrix)+":"+actor.getFrame();
            Vector3D force=actor.character.speedNoFriction;s+=":"+force.x+":"+force.y+":"+force.z;
            for(String name:new String[]{"nextFront","nextSide","nextBack","currentSprite"}) {
                Object value=get(actor,name);int tag=-1;for(int i=0;i<ANIMS.length;i++)if(value==ANIMS[i])tag=i;s+=":"+tag;
            }
            Vector3D dir=(Vector3D)get(actor,"dir");s+=":"+dir.x+":"+dir.y+":"+dir.z;
            for(GameObject t:targets)s+=":"+t.getHp();
            Player p=(Player)targets[0];s+=":"+p.frags+":"+p.money+":"+scene.scriptsToExec.size();
            for(int i=0;i<scene.scriptsToExec.size();i++) {
                Object[] work=(Object[])scene.scriptsToExec.elementAt(i);s+=":"+id(work[0])+":"+Arrays.toString((String[])work[1]);
            }
            Vector u=(Vector)get(actor,"unicalEnemies");for(int i=0;i<u.size();i++)s+=":"+id(u.elementAt(i));return s;
        }
        void event(int frame)throws Exception {
            for(int i=0;i<targets.length;i++)targets[i].setPos((i%3-1)*700+frame*3,0,(block && frame<10?3000:frame/20%2==0?500:-500)+i*350);
            if(frame==15)actor.damage(targets[4],2);
            if(frame==30){targets[2].setHp(0);((Vector)get(actor,"unicalEnemies")).addElement(targets[2]);}
            if(frame==45)put(actor,"enemy",targets[3]); // old enemy retention, even when faction/visibility changed
            if(frame==60){targets[0].setPos(0,0,12000);targets[1].setHp(1);}
            if(frame==80){put(actor,"friendlyFire",new int[]{0});actor.damage(targets[0],1);}
            if(frame==100)actor.damage(targets[4],1000);
        }
    }
    private static void actions()throws Exception {
        long rays=0,routes=0,blocked=0,scripts=0;
        for(int seed=0;seed<60;seed++) {
            Bot.raycastTargetVisibility=seed%2==0;Fixture a=new Fixture(true,seed),b=new Fixture(false,seed);
            for(int frame=0;frame<120;frame++) {
                AIStats.now=1000000+frame*50;QFPS.frameTime=new int[]{16,50,100,33}[frame%4];
                a.event(frame);b.event(frame);
                AIStats.reset();a.actor.update(a.scene,(Player)a.targets[0]);long oldRays=AIStats.rays,oldRoutes=AIStats.routes,oldBlocked=AIStats.blocked;
                AIStats.reset();b.actor.update(b.scene,(Player)b.targets[0]);
                check(AIStats.rays==oldRays && AIStats.routes==oldRoutes && AIStats.blocked==oldBlocked,"raycast/path timing changed");rays+=oldRays;routes+=oldRoutes;blocked+=oldBlocked;
                check(a.state().equals(b.state()),"NPC action state differs seed="+seed+" frame="+frame+"\n"+a.state()+"\n"+b.state());
            }
            scripts+=a.scene.scriptsToExec.size();
            a.scene.getG3D().destroy();b.scene.getG3D().destroy();
        }
        check(rays>0 && routes>0 && blocked>0 && scripts>0,"raycast/portal/blocked LoS/reward scenarios not exercised: "+rays+"/"+routes+"/"+blocked+"/"+scripts);
        System.out.println("AI_COVERAGE updates=7200 rays="+rays+" routes="+routes+" blocked="+blocked+" deathScripts="+scripts);
        System.out.println("PASS 7200 paired NPC updates / original reaction+attack schedules / variable frameTime / enemy+follower+unique+old target / damage sleep+friendly fire+death / portal routes+raycast timing / HP+movement+rewards unchanged");
    }
    private static void profile()throws Exception {
        NPC[] bots=new NPC[96];CountingVector objects=new CountingVector();
        for(int i=0;i<bots.length;i++) {
            NPC n=bots[i]=new NPC(new Vector3D(),null,100,null);n.toAttack=new int[]{2};n.toFollow=new int[]{-1};
            n.setPos((i%8)*3500,0,(i/8)*3500);n.character.transform.m02=0;n.character.transform.m22=16384;objects.addElement(n);
        }
        for(int scenario=0;scenario<3;scenario++) {
            String name=new String[]{"neutral96","mixed96","mixed96_fov"}[scenario];
            for(int i=0;i<bots.length;i++){bots[i].fraction=scenario==0?1:i%4==0?2:1;bots[i].visiblityCheck=scenario==2;}
            GameObject[] enemies=new GameObject[96],followers=new GameObject[96];
            AIStats.reset();
            for(int i=0;i<bots.length;i++){enemies[i]=AIReference.findBot(bots[i],objects,bots[i],bots[i].toAttack);followers[i]=AIReference.findBot(bots[i],objects,bots[i],bots[i].toFollow);}
            String before=AIStats.json();long reads=AIStats.reads,distances=AIStats.distances,vectors=AIStats.vectors;
            AIStats.reset();
            for(int i=0;i<bots.length;i++){select(bots[i],objects);check(get(bots[i],"enemy")==enemies[i] && get(bots[i],"follower")==followers[i],"profile target changed");}
            check(AIStats.reads<reads && AIStats.distances<=distances && AIStats.vectors==0,"profile did not remove redundant work");
            check(AIStats.scans==96 && AIStats.reads==9216,"dual-role scan/read budget regressed");
            check(AIStats.distances==(scenario==0?0:2280),"faction/distance reuse budget regressed");
            if(scenario==2) {
                check(vectors>0,"FOV allocation baseline not exercised");
                check(AIStats.sqrts<=264,"certified FOV sqrt budget regressed");
            }
            System.out.println("AI_WORK scenario="+name+" before="+before+" after="+AIStats.json());
        }
        System.out.println("PASS deterministic 96-NPC work profiles / same targets / no per-search allocations / not FPS or ARM9 timings");
    }
    public static void main(String[] args)throws Exception {
        Main.blood=false;Shop.weaponCount=0;Shop.defaultArsenal=null;
        Blood.blood=new Texture(new RawImage(new int[]{0xffffffff,0xffffffff,0xffffffff,0xffffffff},2,2),true,null);
        for(int i=0;i<ANIMS.length;i++)ANIMS[i]=new Sprite(Blood.blood,5);
        fov();selectors();actions();profile();
    }
}
