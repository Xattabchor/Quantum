package code.utils;

/**
 *
 * @author Roman
 */
public class QFPS extends FPS {
	
	public static void frame() {
		// Кадровый кэш времени: покадровые таймеры анимации (Sprite, Texture,
		// NPC, Player) берут время отсюда вместо нативного вызова на каждый объект.
		FrameClock.tick();
		FPS.frame();
		if(frameTime < 1) frameTime = 1;
		else if(frameTime > 200) frameTime = 200;
	}
	
}
