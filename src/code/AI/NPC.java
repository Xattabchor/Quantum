package code.AI;

import code.Gameplay.Map.House;
import code.Gameplay.Map.Portal;
import code.Gameplay.Map.Scene;
import code.Gameplay.Objects.GameObject;
import code.Math.FastMath;
import code.Math.MathUtils;
import code.Math.Matrix;
import code.Math.Vector3D;
import code.Rendering.DirectX7;
import code.Rendering.Meshes.BoundingBox;
import code.Rendering.Meshes.MeshImage;
import code.Rendering.Meshes.Sprite;
import code.Rendering.MultyTexture;
import code.Rendering.Vertex;
import code.Gameplay.GameScreen;
import code.utils.FrameClock;
import java.util.Vector;

public final class NPC extends Bot {

    private static final Matrix tmpMatrix = new Matrix();

    public int animspeed = 140;//140
    public int attackanimspeed = 700;//700
    public int damage = 1;//1
    public float attackradius = 1.2F;//1.2F
    public int jumpheight = 140;//140
    public float jumpspeed = 1.2F;//1.2F
    public int speed = 140;//140
    public int reacttimer = 8;//8
    public int attacktimer = 8;//8
    
    public static final int WALK_STATE = 1;
    public static final int ATTACK_STATE = 2;
    
    public int attackState = ATTACK_STATE;//2
    public int whenEnemyIsFar = WALK_STATE;//1
    public int whenEnemyIsNear = ATTACK_STATE;//2
    
    public int moneyOnDeath = 0;
    public int fragsOnDeath = 0;
    public int fragsOnAnyDeath = 0;
    public int damageSleepTime = 0;
    private long lastDamage = 0L;
    public long maxEnemyDistance = -1L;
    public boolean attackOnDamageOnlyPlayer = false;
    public int spawnerId = -1;

    private int maxHp = 100;
    public int model_height; // высота по y
    private int state = -1;

    public MeshImage meshImage;

    public int[] toAttack;
    public int[] toFollow;
    
    public boolean inPlayerTeam;
    public int[] friendlyFire;
    
    public String[] onDeath;

    // --- LIFT / транспорт-имитация патруля (дешево для ARM9) ---
    public Vector3D[] liftPoses;
    public int[] liftTime;
    public int liftState = 0; // 0=STAY_BEGIN,1=DRIVE,2=STAY_END,3=DRIVE_BACK
    public long liftStartTime;
    public long liftPauseTime;
    public long liftPauseBegin;
    public boolean liftPaused;
    public boolean liftCycled;
    public boolean liftReUse = true;
    public boolean liftCanBePaused;
    public boolean liftRotateToMove = true;
    public boolean liftDisactivateOnEnd;
    private final Vector3D liftTmp = new Vector3D();

    private static final int LIFT_STAY_BEGIN = 0, LIFT_DRIVE = 1, LIFT_STAY_END = 2, LIFT_DRIVE_BACK = 3;

    private void getLiftPos(Vector3D out, long coof, int summ) {
        int a = 0; int sub = 0;
        while (coof > summ && liftCycled) coof -= summ;
        while (coof < 0 && liftCycled) coof += summ;
        if (coof > summ) coof = summ;
        if (coof < 0) coof = 0;
        for (int i = 0; i < liftTime.length; i++) { sub += liftTime[i]; if (coof >= sub) a++; }
        if (a >= liftPoses.length && liftCycled) a = 0;
        if (a >= liftPoses.length) a = liftPoses.length - 1;
        if (a < 0) a = 0;
        int b = a + 1;
        if (b >= liftPoses.length && liftCycled) b = 0;
        if (b >= liftPoses.length) b = liftPoses.length - 1;
        if (b < 0) b = 0;
        sub = 0;
        if (a >= liftTime.length) a = liftTime.length - 1;
        for (int i = 0; i < a; i++) sub += liftTime[i];
        int c = (int) ((coof - sub) * 1000 / liftTime[a]);
        int d = 1000 - c;
        out.set((liftPoses[a].x * d + liftPoses[b].x * c) / 1000,
                (liftPoses[a].y * d + liftPoses[b].y * c) / 1000,
                (liftPoses[a].z * d + liftPoses[b].z * c) / 1000);
    }

    private void updateLift() {
        if (liftPoses == null || liftTime == null) return;
        if (liftState == LIFT_STAY_BEGIN) { character.getTransform().setPosition(liftPoses[0]); return; }
        if (liftState == LIFT_STAY_END) { character.getTransform().setPosition(liftPoses[liftPoses.length - 1]); return; }
        if (liftState != LIFT_DRIVE && liftState != LIFT_DRIVE_BACK) return;
        if (liftPaused) { liftPauseTime += GameScreen.time - liftPauseBegin; liftPauseBegin = GameScreen.time; return; }
        int summ = 0; for (int i = 0; i < liftTime.length; i++) summ += liftTime[i];
        long coof = GameScreen.time - liftPauseTime - liftStartTime;
        if (coof >= summ && liftCycled) { coof -= summ; liftStartTime += summ; }
        if (liftState == LIFT_DRIVE && coof >= summ) { liftState = LIFT_STAY_END; if (liftDisactivateOnEnd) activable = false; return; }
        if (liftState == LIFT_DRIVE_BACK && coof >= summ) { liftState = LIFT_STAY_BEGIN; return; }
        if (liftState == LIFT_DRIVE_BACK) coof = summ - coof;
        getLiftPos(liftTmp, coof, summ);
        character.getTransform().setPosition(liftTmp);
        if (liftRotateToMove) {
            Vector3D nxt = new Vector3D(); getLiftPos(nxt, coof + 120, summ);
            if (nxt.x != liftTmp.x || nxt.z != liftTmp.z) lookAt(nxt.x, nxt.z);
        }
    }

    // резкий поворот как в MeshObject.lookAtDirect
    private void lookAtDirect(int x, int z) {
        Matrix pos = getCharacter().getTransform();
        dir.set(pos.m03 - x, 0, pos.m23 - z);
        dir.setLengthFast(-Matrix.FP);
        pos.setDir(dir.x, 0, dir.z);
        // side/up пересчитает Character, достаточно setDir
        Vector3D side = new Vector3D(); side.cross(new Vector3D(0, Matrix.FP, 0), dir, Matrix.fp); side.setLengthFast(Matrix.FP);
        if (dir.lengthSquared() != 0 && side.lengthSquared() != 0) {
            pos.setSide(side.x, side.y, side.z);
            pos.setUp(0, Matrix.FP, 0);
        }
    }

    public final void activate(code.Gameplay.Map.House house, Player player, code.Gameplay.GameScreen gs) {
        if (liftPoses != null && liftPoses.length > 1) {
            if (lastActivate >= 0 && lastActivate + timeToReset > GameScreen.time) return;
            if (liftState == LIFT_DRIVE || liftState == LIFT_DRIVE_BACK) {
                if (liftCanBePaused) { liftPaused = !liftPaused; liftPauseBegin = GameScreen.time; }
            } else if (liftState == LIFT_STAY_BEGIN || liftState == LIFT_STAY_END) {
                liftStartTime = GameScreen.time; liftPauseTime = 0;
                if (liftState == LIFT_STAY_BEGIN) liftState = LIFT_DRIVE;
                else if (liftState == LIFT_STAY_END && liftReUse) liftState = LIFT_DRIVE_BACK;
            }
        }
        super.activate(house, player, gs);
    }

    private GameObject enemy;
    private GameObject follower;
    public Vector unicalEnemies;
    private boolean walkingToEnemy;
    
    public final MultyTexture mt;
    private final Vector3D dir = new Vector3D();

    public Sprite stayFront, stayBack, staySide;
    public Sprite attackFront, attackBack, attackSide;
    public Sprite deathFront, deathBack, deathSide;
    public Sprite damageFront, damageBack, damageSide;
    public Sprite walkFront, walkBack, walkSide;
    public Sprite currentSprite, nextFront, nextBack, nextSide;
    
    public Sprite muzzleFlash;
    public Vertex muzzleFlashPos;
    public int muzzleFlashTimer;
    public long lastAttack;

    public BoundingBox boundingBox;

    public NPC(Vector3D pos, MeshImage mi, int hp, MultyTexture mts) {
        maxHp = hp;
        meshImage = mi;
        if (mi != null) {
            model_height = mi.getMesh().maxY() - mi.getMesh().minY();
            boundingBox = new BoundingBox(meshImage.getAnimation());
        }
        mt = mts;
        fraction = 3;//Default bot fraction
        toAttack = new int[]{1, 2};//Attack Zombie and Bigzombie fractions
        toFollow = new int[]{0};//Follow player fraction
        name = "NPC";//For scripts
        visiblityCheck = true;
        init(pos);
    }

    public final void init(Vector3D pos) {
        if (meshImage != null) meshImage.setFrame(0);
        super.set(pos);
        setHp(maxHp);
        
        dir.set(pos.x + 50, pos.y, pos.z + 50);
        lookAt(pos.x + 50, pos.z + 50);
        
        setSprite(stayFront, staySide, stayBack);
        if (stayFront != null) model_height = stayFront.getHeight()*((stayFront.textures[0].rImg.scale<2)?2:1);
        
        setCharacterSize(model_height);
        
        if (unicalEnemies != null) unicalEnemies.removeAllElements();
        lastDamage = 0;
        state = -1;
        enemy = follower = null;
        currentSprite = null;
        walkingToEnemy = false;
    }
    
    public final void initStaySprite() {
        setSprite(stayFront, staySide, stayBack);
        if (stayFront != null) model_height = stayFront.getHeight()*((stayFront.textures[0].rImg.scale<2)?2:1);
    }

    public final void destroy() {
        super.destroy();
        enemy = follower = null;
        meshImage = null;
        boundingBox = null;
    }

    public final boolean checkVisiblity(DirectX7 g3d, int x, int y, int z, int x1, int y1, int x2, int y2) {

        if (boundingBox != null) {
            tmpMatrix.setIdentity();
            tmpMatrix.setPosition(x, y, z);
            Matrix fmat = g3d.computeFinalMatrix(tmpMatrix);
            return boundingBox.isVisible(g3d, fmat, x1, y1, x2, y2);
        }

        if (stayFront != null) {
            stayFront.getPosition().set(x, y, z);
            stayFront.project(g3d.getInvCamera(), g3d);
            return stayFront.isVisible( x1, y1, x2, y2);
        }

        return false;
    }
    
    public final void render(DirectX7 g3d, int x1, int y1, int x2, int y2) {
        if(!visible) return;
        

        if(meshImage != null) {
            Matrix mat = character.getTransform();
            Matrix fmat = g3d.computeFinalMatrix(mat);
            if(boundingBox != null && !boundingBox.isVisible(g3d, fmat, x1, y1, x2, y2)) return;

            if(mat.m11 == 16384) { //if not falling
                if(state == WALK_STATE) meshImage.setFrame(FastMath.div100(getFrameInter() * animspeed));
                if(state == ATTACK_STATE) meshImage.setFrame(FastMath.div100(getFrameInter() * attackanimspeed));
            }

            meshImage.setMatrix(fmat);
            meshImage.setTexture(mt);
            g3d.addRenderObject(meshImage, x1, y1, x2, y2);
            meshImage.sz += character.getRadius();
            if (character.oldFloorPoly != null && character.oldFloorPoly.sz > meshImage.sz) character.oldFloorPoly.sz = meshImage.sz - 1;
        } else {
            setSprite(g3d);
            
            if(currentSprite != null) {
                currentSprite.getPosition().set(getPosX(), getPosY(), getPosZ());

                currentSprite.updateFrame();
                currentSprite.project(g3d.getInvCamera(), g3d);

                if(!currentSprite.isVisible(x1, y1, x2, y2)) return;

                g3d.addRenderObject(currentSprite, x1, y1, x2, y2);
                currentSprite.sz += character.getRadius() * 2;
                if(character.oldFloorPoly != null && character.oldFloorPoly.sz > currentSprite.sz)
                    character.oldFloorPoly.sz = currentSprite.sz - 1;
            }
        }
        
        if(muzzleFlash != null && FrameClock.time() - lastAttack <= muzzleFlashTimer) {
            muzzleFlashPos.transform(character.transform);
            muzzleFlash.pos.set(muzzleFlashPos.sx, muzzleFlashPos.sy - FastMath.div2(muzzleFlash.getHeight()), muzzleFlashPos.rz);
            muzzleFlash.project(g3d.getInvCamera(), g3d);
            g3d.addRenderObject(muzzleFlash, x1, y1, x2, y2);
            muzzleFlash.sz += character.getRadius();
        }
        renderBlood(g3d, 1500);
    }
    
    public void setSprite(DirectX7 g3d) {
        Sprite nextSpr = null;
        int degree = 0;
        
        if(g3d.camera != null) {
            degree = MathUtils.fixDegree(180 + MathUtils.getAnglez(getPosX(), getPosZ(), 
                    g3d.camera.m03, g3d.camera.m23) - character.getTransform().getRotZ());
        }
        
        if(nextFront != null) nextSpr = nextFront;
        
        if(nextBack != null && degree > 90 && degree < 270) nextSpr = nextBack;
        
        if(nextSide != null && degree > 45 && degree < 135) {
            nextSide.mirX = false;
            nextSpr = nextSide;
        }
        
        if(nextSide != null && degree > 225 && degree < 315) {
            nextSide.mirX = true;
            nextSpr = nextSide;
        }

        if(currentSprite != null && nextSpr != null && (currentSprite == deathFront || currentSprite == deathSide || currentSprite == deathBack)) return;
        
        if(currentSprite != null && nextSpr != null && 
                (currentSprite == damageFront || currentSprite == damageSide || currentSprite == damageBack ||
                 currentSprite == attackFront || currentSprite == attackSide || currentSprite == attackBack)
                && !
                (nextSpr == damageFront || nextSpr == damageSide || nextSpr == damageBack)) {//Checking for animation end
            if((long) (currentSprite.animationBegin - FrameClock.time()) 
                    >= 
                    -currentSprite.getPeriodMs()) return;  
        }

        if(nextSpr == null || nextSpr == currentSprite) return;
        currentSprite = nextSpr;
        
        if(currentSprite != null) currentSprite.animationBegin = System.currentTimeMillis();
    }

    protected final void action(Scene scene) {
        if (liftPoses != null) updateLift();
        if (FrameClock.time() - lastDamage < damageSleepTime) return;
        
        // LIFT-транспорт: если едет по рельсам, AI движения не нужен — только атака
        final boolean onLift = liftPoses != null && (liftState == LIFT_DRIVE || liftState == LIFT_DRIVE_BACK || liftState == LIFT_STAY_BEGIN || liftState == LIFT_STAY_END);
        
        // AI-LOD: далёкому боту искать цель и перестраивать маршрут достаточно
        // вдвое реже - на экране он занимает несколько пикселов, а этот блок
        // тянет findBot() по всем объектам сцены и проверку видимости.
        final int react = aiFar ? (reacttimer << 1) : reacttimer;
        if (Math.abs(getFrameInterDiv()) % react == 2) {
            House house = scene.getHouse();
            Vector objs = house.getObjectsCached();   // ARM9: перестройка списка максимум раз в кадр
            
            //Remove dead from unicalEnemies list
            if(unicalEnemies != null && !unicalEnemies.isEmpty()) {
                for(int i = 0; i < unicalEnemies.size(); ++i) {
                    GameObject tmp = (GameObject) unicalEnemies.elementAt(i);

                    if(tmp.isDead()) {
                        unicalEnemies.removeElementAt(i);
                        i--;
                    }
                }
            }
            
            //Forget if enemy is dead
            if(enemy != null && enemy.isDead()) enemy = null;
            
			//Find new enemy
            GameObject oldEnemy = enemy;
            enemy = findBot(objs, this, toAttack);
            
			//Or select a closer enemy from unicalEnemies list
            unicalEnemy_search:
            if(unicalEnemies != null) {
                GameObject enemy2 = findBot(unicalEnemies, this, null);
                if(enemy2 == null) break unicalEnemy_search;
                
                //Select nearest
                if(enemy == null) enemy = enemy2;
                else if(character.distance(enemy2.getCharacter()) < character.distance(enemy.getCharacter())) {
                    enemy = enemy2;
                }
            }
            
            //Select old enemy if it's closer
            if(oldEnemy != null) {
                if(enemy == null) enemy = oldEnemy;
                else if(character.distance(oldEnemy.getCharacter()) < character.distance(enemy.getCharacter())) {
                    enemy = oldEnemy;
                }
            }
            
            //Forget enemy if it's too far
            if(enemy != null && maxEnemyDistance > -1) {
                if(character.distance(enemy.getCharacter()) >= maxEnemyDistance * maxEnemyDistance) 
                    enemy = null;
            }
            
            follower = findBot(objs, this, toFollow);

            GameObject target = enemy != null ? enemy : follower;
            
            if(target != null) {
				if(follower != null) {
					long followerDist = character.distance(follower.getCharacter());
					
					//If follower more than in ~10 meters
					if(followerDist >= 90000000L) target = follower;

					//If follower is less than in 3.38 meters
					if(target == follower && followerDist < 9000000) {
						target = enemy;
					}
				}
                
                state = -1;
                
                //forget bot if bot is a new enemy and enemy isnt visible
                if(
					target != null &&
					target == enemy && 
					enemy != oldEnemy && 
					!isTargetVisibleRaycast(house, target)
				) {
                    target = null;
                    enemy = null;
                }
                
                if(target != null) {
					walkingToEnemy = getPart() == target.getPart();
                    
					if(walkingToEnemy) {
                        Matrix mat = target.getCharacter().getTransform();
                        dir.set(mat.m03, mat.m13, mat.m23);
                    } else {
                        int nextPart = scene.getNext(getPart(), target.getPart());
                        Portal portal = commonPortal(house, getPart(), nextPart);
                        if(portal != null) computeCentre(portal, dir);
                    }

                    if (!onLift || target == enemy) lookAt(dir.x, dir.z);

                    long dist = character.distance(target.getCharacter());
                    if (walkingToEnemy && 
                            dist <= sqr(character.getRadius() + target.getCharacter().getRadius()) * attackradius) {
                        if(target == enemy) state = whenEnemyIsNear;
                        
                    } else {
                        if (!onLift) { if (character.isCollision()) character.jump(jumpheight, jumpspeed); }
                        state = whenEnemyIsFar;
                    }
                }
            }
        }
        
        if (state == WALK_STATE && !onLift) walk(speed);
        
        if (state == attackState && getFrameInterDiv() % attacktimer == 0 && enemy != null && walkingToEnemy) 
            attack(scene, enemy, damage);
        

    }

    protected final void drop(Scene scene) {
        super.drop(scene);
        state = -1;
    }

    private void walk(int spd) {
        character.moveZ(spd);
        setSprite(walkFront, walkSide, walkBack);
    }

    private void attack(Scene scene, GameObject en, int dam) {
        boolean wasAlive = !en.isDead();
        
        en.damage(this, dam);
        setSprite(attackFront, attackSide, attackBack);
        if(muzzleFlash!=null) lastAttack=FrameClock.time();
        
        if(inPlayerTeam && wasAlive && en.isDead()) {
            if(en instanceof NPC) {
                NPC npc = (NPC) en;
                
                Player player = scene.findPlayer();
                if(player != null) {
                    player.frags += npc.fragsOnDeath;
                    player.money += npc.moneyOnDeath;
                }
                
                scene.runScript(new Object[]{npc, npc.onDeath});
            }
        }
    }

    public void setSprite(Sprite front, Sprite side, Sprite back) {
        nextFront = front;
        nextSide = side;
        nextBack = back;
    }
    
    public boolean isTimeToRenew() {
        if(currentSprite != null) {
            if(!(currentSprite == deathFront || currentSprite == deathSide || currentSprite == deathBack) && isDead()) {
                setSprite(deathFront, deathSide, deathBack);
            }
            if(currentSprite.animationBegin - FrameClock.time() < -currentSprite.getPeriodMs()
                    && (currentSprite == deathFront || currentSprite == deathSide || currentSprite == deathBack)) {
                return isDead();
            }
            return false;
        }
        
        return super.isTimeToRenew();
    }

    public boolean damage(GameObject obj, int dmg) {
        if (friendlyFire != null) {
            if (obj instanceof Bot) {
                if(Bot.contains(friendlyFire, (((Bot) obj).fraction))) return false;
            } else if (obj instanceof Player) {
                if(Bot.contains(friendlyFire, 0)) return false;
            }
        }
        
        if(dmg >= 0) {
            if (System.currentTimeMillis() - lastDamage > damageSleepTime) lastDamage = System.currentTimeMillis();
            int tf = -1;
            if(obj instanceof Player) tf = 0;
            
            if(obj instanceof Bot && !attackOnDamageOnlyPlayer) tf = ((Bot) obj).fraction;
            
            boolean canAttack = false;
            
            if(tf != -1) {
                boolean inToAttack = contains(toAttack, tf);
                canAttack |= inToAttack;
                
                if(unicalEnemies != null && !inToAttack) {
                        if(!unicalEnemies.contains(obj)) unicalEnemies.addElement(obj);
                        canAttack |= true;
                }
            }
            if(getHp() - dmg > 0) setSprite(damageFront, damageSide, damageBack);
            
            if(enemy == null && canAttack) enemy = obj;
        }
        
        return super.damage(obj, dmg);
    }
}