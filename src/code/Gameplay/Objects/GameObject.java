package code.Gameplay.Objects;

import code.Gameplay.GameScreen;
import code.AI.Player;
import code.Gameplay.Map.Character;
import code.Gameplay.Map.House;
import code.Gameplay.Map.RoomObject;
import code.Gameplay.Map.Scene;
import code.Math.Vector3D;
import code.Math.FastMath;
import code.utils.Asset;
import code.utils.QFPS;
import code.utils.Main;

public abstract class GameObject extends RoomObject {

    private int frame;
    private int frame100;
    private float friction = 4f;
    // Кэш для целочисленного пути: если friction - целое (обычно 4f),
    // деление можно делать в int, без трёх float-делений на объект за кадр.
    private int frictionInt = 4;
    private boolean frictionIsInt = true;
    public final Character character = new Character(0, 0);
    private int hp;
    public long DeathTime = 0l;

    public final void setCharacterSize(int modelHeight) {
        character.set((int) (modelHeight / 2.5F), (int) (modelHeight * 0.75F));
    }

    public final void setCharacterSize(int w, int h) {
        character.set(w, h);
    }

    public final void setCharacterSize(int w, int wz, int h) {
        character.set(w, wz, h);
    }

    protected final void jump(int jump, float force) {
        character.jump(jump, force);
    }

    public void update(Scene scene, Player player) {
        if(character.isUpdatable()) {
            character.update();
            character.collisionTest(getPart(), scene.getHouse());
            
            if(character.isOnFloor() || character.fly) {
                Vector3D speed = character.getSpeed();

                if(frictionIsInt) {
                    // (int)((float)(v / 4f)) == v / 4 для |v| < 2^24: float-деление
                    // корректно округляется, а приведение к int усекает к нулю -
                    // то же самое делает целочисленное деление.
                    speed.x /= frictionInt;
                    speed.y /= frictionInt;
                    speed.z /= frictionInt;
                } else {
                    speed.x = (int) ((float) (speed.x / friction));
                    speed.y = (int) ((float) (speed.y / (friction/**
                             * 200.0F/(float)QFPS.getFps()
                             */
                            )));
                    speed.z = (int) ((float) (speed.z / friction));
                }
            }
        }

        frame++;
        frame100 += 100 * QFPS.frameTime / 50;
    }

    // true - если персонаж убит
    public boolean damage(GameObject obj, int dmg) {
        return damage(dmg);
    }

    public boolean damage(int dmg) {
        boolean oldDead = isDead();
        hp -= dmg;
        if(hp < 0) hp = 0;

        if(oldDead != isDead()) {
            frame = 0;
            frame100 = 0;
            DeathTime = System.currentTimeMillis();
            return true;
        } else {
            return false;
        }
    }

    public final Character getCharacter() {
        return character;
    }

    public final int getHp() {
        return hp;
    }

    public final boolean isDead() {
        return hp <= 0;
    }

    public boolean isTimeToRenew() {
        return isDead() && frame > 25 * 50 / (QFPS.frameTime==0?1:QFPS.frameTime);
    }

    public final void setHp(int hp) {
        this.hp = hp;
    }

    public final int getFrame() {
        return frame;
    }

    public final void setFriction(float f) {
        friction = f;
        frictionInt = (int) f;
        frictionIsInt = (frictionInt > 0 && (float) frictionInt == f);
    }

    public final float getFriction() {
        return friction;
    }

    public final int getFrameInter() {
        return this.frame100;
    }

    public final int getFrameInterDiv() {
        // frame100 / 100 вызывается по несколько раз на кадр на каждого бота
        // (проверки реактивного таймера и таймера атаки) - idiv заменён на
        // умножение и сдвиг, результат бит-в-бит тот же.
        return FastMath.div100(this.frame100);
    }

    public final int getPosX() {
        return this.character.getTransform().m03;
    }

    public final int getPosZ() {
        return this.character.getTransform().m23;
    }

    public final int getPosY() {
        return this.character.getTransform().m13;
    }

    public final void setPos(int x, int y, int z) {
        character.getTransform().m03 = x;
        character.getTransform().m13 = y;
        character.getTransform().m23 = z;
    }

    public void activate(House house, Player player, GameScreen gs) {
        if(lastActivate >= 0 && (lastActivate + timeToReset > GameScreen.time)) return;
        if(!isAllCollected(Player.usedPoints, player, house, gs)) {
            if(errMessage != null) errMsg(gs);
            return;
        }
        if(sound != null && Main.isSounds && Main.sounds != 0) Asset.getSound(sound).start(Main.sounds);

        if(!singleUse || !activated) if(message != null) prMsg(gs);
        if(singleUse == false || this.activated == false) give(additional, player, house, gs);
        if(!contains(Player.usedPoints, name)) {
            give(name, player);
            this.activated = true;
            if(timeToReset > 0) lastActivate = GameScreen.time;
        }

        if(destroyOnUse) house.removeObject(this);

    }

}
