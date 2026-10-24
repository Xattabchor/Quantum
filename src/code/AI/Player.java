package code.AI;

import code.Gameplay.Arsenal;
import code.Gameplay.GameScreen;
import code.Gameplay.Inventory.ItemList;
import code.Gameplay.Map.House;
import code.Gameplay.Map.RoomObject;
import code.Gameplay.Map.Scene;
import code.Gameplay.Objects.GameObject;
import code.Gameplay.Weapon;
import code.HUD.Base.HUDInfo;
import code.HUD.DeveloperMenu;
import code.Math.Matrix;
import code.Math.Vector3D;
import code.Rendering.DirectX7;
import code.utils.Asset;
import code.utils.FrameClock;
import code.utils.QFPS;
import code.utils.Main;
import code.utils.WeaponCreator;
import java.util.Vector;

public final class Player extends GameObject {
    public static boolean arcadeJumpPhysics = false;
    public static boolean fallDamage = true;
	public static int walkSpeed = 150;
	public static int radiusOverwrite = -1, heightOverwrite;
    
    public static Vector toAddOnStart = new Vector();
    public static Vector usedPoints = new Vector();
    
    public int money = 0;
    public int frags = 0;
    public Arsenal arsenal;
    private boolean damage = false;
    
    public boolean zoom = false;
    public float fov = Main.stdFov;
    public int stdFov = Main.stdFov, zoomFov = Main.zoomFov;
    public long lastZoomAction = 0L;
    private int falldist = 0;
    private long lastYCheck = 0;
    private int lastY = 0;
    public float rotateX = 0.0f, rotateY = 0.0f;
    
    public ItemList items;
    
    int walkFrame, attackFrame;
    
    public Player(int wG3D, int hG3D, Vector3D pos, Object hudInfo) {
        set(wG3D, hG3D, pos, hudInfo);
    }

    public final void set(int wG3D, int hG3D, Vector3D pos, Object hudInfo) {
        name = "PLAYER";
        character.reset();
        character.getTransform().setPosition(0, 0, 0);
        if (pos != null) character.getTransform().setPosition(pos.x, pos.y, pos.z);
        setHp(100);
        frags = 0;
        setCharacterSize(400, 1503);
        falldist = 0;
        money = 0;
        rotateX = rotateY = 0;

        arsenal = new Arsenal(wG3D, hG3D);
        if(hudInfo != null) { //todo удалить эту хрень
            money = ((HUDInfo) hudInfo).money;
            int[] ammos = ((HUDInfo) hudInfo).ammo;
            Weapon[] weapons = arsenal.getWeapons();

            for (int i=0; i<weapons.length; i++) {
                if (weapons[i] != null) weapons[i].reset();

                if (ammos[i] == -1) weapons[i] = null;
                else {
                    weapons[i] = WeaponCreator.createWeapon(i);
                    weapons[i].setAmmo(ammos[i]);
                }
            }
            if (arsenal.current != -1) arsenal.currentWeapon().createSprite(wG3D, hG3D);
        }
        
        items = new ItemList();
        character.fly = (DeveloperMenu.fly > 0);
        walkFrame = 0;
        attackFrame = Integer.MAX_VALUE;
		if(radiusOverwrite != -1) character.set(radiusOverwrite, character.getHeight());
		if(heightOverwrite != -1) character.set(character.getRadius(), heightOverwrite);
    }

    public final void destroy() {
        money = 0;
    }

    public final void render(DirectX7 g3d, int x1, int y1, int x2, int y2) {
        if(arsenal.currentWeapon() != null) arsenal.currentWeapon().renderSplinter(g3d);
    }
    
    public void nextWeapon() {
        int old = arsenal.current;
        arsenal.next();
        if(old != arsenal.current) {
            attackFrame = Integer.MAX_VALUE;
        }
    }
    public void previousWeapon() {
        int old = arsenal.current;
        arsenal.previous();
        if(old != arsenal.current) {
            attackFrame = Integer.MAX_VALUE;
        }
    }

    public final void update(Scene scene, Player player) {
        super.update(scene, player);
        
                if(fallDamage) {
        if (character.onFloor == false && FrameClock.time() - lastYCheck < 1000) {
            falldist += lastY - getPosY();
        }
        if(falldist > 7000 && character.onFloor == true) {
            damage = true;
            damage((falldist - 7000) / 40);
        }
        if(falldist > 11000 && character.onFloor == false) {
            damage = true;
            damage(100);
        }
        lastYCheck = System.currentTimeMillis();
        lastY = getPosY();
        if (character.onFloor || character.fly) falldist = 0;
        }
        
        if(arsenal.current != -1) {
            if (arsenal.currentWeapon() != null) {
                GameObject obj = arsenal.currentWeapon().update(scene.getHouse(), this);
                if(obj instanceof Zombie) {
                    money += Zombie.moneyOnDeath;
                    frags++;
                } else if (obj instanceof BigZombie) {
                    money += BigZombie.moneyOnDeath;
                    frags++;
                } else if (obj instanceof NPC) {
                    NPC npc = (NPC) obj;
                    frags += npc.fragsOnDeath;
                    money += npc.moneyOnDeath;
                    scene.runScript(new Object[]{npc,npc.onDeath});
                }
            }
        }
		
    }
    
    // Вид от третьего лица (TPPose, thirdperson.txt) вырезан полностью:
    // методы возвращают прежние дефолты первого лица.
    public boolean canWalk() { return true; }
    
    public boolean canJump() { return true; }
    
    public boolean canLookX() { return true; }
    
    public boolean canLookY() { return true; }
    
    public boolean canAttack() { return true; }
    
    public boolean canAttackSight() { return true; }
    
    public boolean canAttackNoSight() { return true; }
    
    public boolean isSwapStrafeLook() { return false; }
    
    public boolean isRotToWalkDir() { return false; }
    
    public boolean show2D() { return true; }
    
    public float lookSpeed() { return (zoom ? 0.71f : 1f); }
    
    public int walkSpeed() { return walkSpeed; }
    
    public void setCamera(DirectX7 g3d) {
        Matrix playerMat = character.getTransform();
        int playerHeight = getEyesHeight();
        playerMat.m13 += playerHeight;
        g3d.setCamera(playerMat);
        playerMat.m13 -= playerHeight;
    }
    
    public int getRenderPart(Scene scene) {
        return getPart();
    }
    
    public int getEyesHeight() {
        int height = 1503;//character.getHeight();
        
        if(isDead()) {
            height = (int) (height / Math.max(0.4F * getFrame(), 1F));
            if(height < character.getRadius()) height = character.getRadius();
        }
        
        return height;
    }

    public final boolean damage(GameObject obj, int dmg) {
        if (DeveloperMenu.debugMode && dmg > 0 && DeveloperMenu.godMode) return true;
        if (dmg > 0) damage = true;
        if (dmg < 0 && getHp() - dmg > 100) return super.damage(obj, -(100 - getHp()));
        
        return super.damage(obj, dmg);
    }

    public final boolean isDamaged() {
        boolean tmp = damage;
        damage = false;
        return tmp;
    }

    public final void pay(int price) {
        money -= price;
    }

    public final boolean isTimeToRenew() {
        return isDead() && (FrameClock.time() - DeathTime) > 3000;
    }

    public final void fire(DirectX7 g3d) {
        if(arsenal.current != -1 && canAttack()) {
            if(arsenal.currentWeapon().fire(g3d)) {
                attackFrame = 0;
            }
        }
    }

    public final void jump() {
        if(!canJump()) return;
        if(!arcadeJumpPhysics) jump(150, 1.2F);
        else character.jumpArcade(150, 1.2F);
    }
    
    public void updateMatrix() {
        int x=character.transform.m03;
        int y=character.transform.m13;
        int z=character.transform.m23;
        
        if(rotateX<-80) rotateX=-80;
        if(rotateX>80) rotateX=80;
        
        while(rotateY>360) rotateY-=360;
        while(rotateY<0) rotateY+=360;
        
        character.transform.setIdentity();
        character.transform.setRotX((int)rotateX);
        character.transform.setPosition(x,y,z);
        character.transform.rotY((int)rotateY);
    }

    public final void rotYn(float i) {
        if(!canLookY()) return;
        rotateY += i;
        updateMatrix();
    }
    
    public final void rotXn(float i) {
        if(!canLookX()) return;
        rotateX += i;
        updateMatrix();
    }
    
    public final void rotLeft() {
        if(!canLookY()) return;
        rotYn((7f * lookSpeed() * Main.mouseSpeed / 50.0F) * QFPS.frameTime / 50f);
    }

    public final void rotRight() {
        if(!canLookY()) return;
        rotYn(-(7f * lookSpeed() * Main.mouseSpeed / 50.0F) * QFPS.frameTime / 50f);
    }
    
     public final void rotUp() {
        if(!canLookX()) return;
        rotXn((7f * lookSpeed() * Main.mouseSpeed / 100.0F) * QFPS.frameTime / 50f);
    }

    public final void rotDown() {
        if(!canLookX()) return;
        rotXn(-(7f * lookSpeed() * Main.mouseSpeed / 100.0F) * QFPS.frameTime / 50f);
    }
	
	public void walk(int right, int forward) {
		if(!canWalk()) return;
			
		int walkSpeed = walkSpeed();
		if(character.fly) walkSpeed = walkSpeed * 7 / 3;
		
		// Ветка третьего лица (поворот модели к направлению ходьбы через
		// cam.currentRotY) вырезана вместе с TPPose - осталось движение
		// от первого лица.
		character.moveZ(-walkSpeed * forward);
		character.moveX(walkSpeed * right);
		
		if((forward != 0 || right != 0) && arsenal.currentWeapon() != null) arsenal.currentWeapon().enableShake();
	}

    public final Object getHUDInfo() {
        Weapon[] weapons = arsenal.getWeapons();
        int[] ammos = new int[weapons.length];

        for (int i=0; i<weapons.length; i++) {
            if (weapons[i] != null) ammos[i] = weapons[i].getAmmo() + weapons[i].getRounds();
            else ammos[i] = -1;
        }

        return new HUDInfo(money, ammos);
    }

    public void activate(House house, Player player, GameScreen gs) {
    }
    
    public void copyNewToUsed() {
        if(toAddOnStart.isEmpty()) return;
        
        while(!toAddOnStart.isEmpty()) {
            if(!RoomObject.containsSimple(usedPoints,(String)toAddOnStart.firstElement())) usedPoints.addElement(toAddOnStart.firstElement());
            toAddOnStart.removeElementAt(0);
        }
    }
    
}
