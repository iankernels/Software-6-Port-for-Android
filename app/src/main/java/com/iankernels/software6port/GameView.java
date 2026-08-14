package com.iankernels.software6port;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * GameView — the core of the Software 6 raycasting engine.
 *
 * This is a SurfaceView that runs its own game loop in a separate thread.
 * Everything is rendered on the CPU using a pixel buffer (int[] array)
 * which is then drawn to a Bitmap and blitted onto the Canvas.
 *
 * No OpenGL or Vulkan is used — this is purely software rendering,
 * similar in concept to the original Wolfenstein 3D engine.
 */
public class GameView extends SurfaceView implements Runnable {

    // ─── Door state machine ───────────────────────────────────────────────
    public enum DoorState { CLOSED, OPENING, OPEN, CLOSING }

    /**
     * Represents a single sliding door on the map.
     * offset goes from 0.0 (fully closed) to 1.0 (fully open).
     * Doors auto-close after holdTime seconds when the player steps away.
     */
    public static class Door {
        public int x, y;              // Grid position on the map
        public float offset = 0.0f;   // 0.0 = closed, 1.0 = fully open
        public DoorState state = DoorState.CLOSED;
        public float timer = 0.0f;    // Countdown for auto-close
        public float openSpeed = 1.0f;
        public float holdTime = 3.0f; // Seconds before door starts closing

        public Door(int x, int y) {
            this.x = x;
            this.y = y;
        }

        /** Toggle between open and close. */
        public void toggle() {
            if (state == DoorState.CLOSED || state == DoorState.CLOSING) {
                state = DoorState.OPENING;
            } else if (state == DoorState.OPEN || state == DoorState.OPENING) {
                state = DoorState.CLOSING;
            }
        }
    }

    // ─── Weapon data ──────────────────────────────────────────────────────

    /**
     * Holds stats and sprites for a single weapon type.
     * Each weapon has an "idle" sprite and a "firing" sprite (muzzle flash).
     */
    public static class Weapon {
        public String name;
        public int ammo;
        public int maxAmmo;
        public int damage;      // Damage per hit (hitscan)
        public float fireRate;  // Seconds between shots
        public Bitmap idleSprite;
        public Bitmap fireSprite;

        public Weapon(String name, int ammo, int maxAmmo, int damage, float fireRate) {
            this.name = name;
            this.ammo = ammo;
            this.maxAmmo = maxAmmo;
            this.damage = damage;
            this.fireRate = fireRate;
        }
    }

    // ─── Game loop machinery ──────────────────────────────────────────────

    private Thread gameThread = null;
    private SurfaceHolder surfaceHolder;
    private volatile boolean running = false;

    private long lastTime;        // Timestamp of the last frame (for delta-time)
    private float fps;            // Smoothed FPS (exponential moving average)
    private long ramUsageBytes;   // Last measured RAM usage

    // ─── Player state ─────────────────────────────────────────────────────

    // Position in the 2D map (float allows sub-tile movement)
    private double posX = 3.5, posY = 3.5;

    // Direction vector (where the player is looking)
    private double dirX = 1.0, dirY = 0.0;

    // Camera plane — perpendicular to dir, determines the FOV.
    // The ratio of plane length to dir length defines the field of view.
    // 0.66 gives roughly 66° FOV (standard for raycasters).
    private double planeX = 0.0, planeY = 0.66;

    // ─── Map data ─────────────────────────────────────────────────────────

    // The level is a 16×16 grid of tiles.
    // Values: 0 = empty (walkable), 1-4 = wall textures, 5 = door tile
    private static final int MAP_WIDTH = 16;
    private static final int MAP_HEIGHT = 16;
    private static final int[][] MAP = {
            {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1},
            {1,0,0,0,0,0,1,0,0,0,0,0,0,0,0,1},
            {1,0,2,2,0,0,1,0,3,3,3,3,0,4,0,1},
            {1,0,2,0,0,0,0,0,3,0,0,3,0,4,0,1},
            {1,0,2,0,0,0,1,0,3,0,0,3,0,4,0,1},
            {1,0,0,0,0,0,1,0,0,0,0,0,0,0,0,1},
            {1,1,1,5,1,1,1,1,1,5,1,1,1,1,0,1},
            {1,0,0,0,0,0,0,0,0,0,0,0,0,1,0,1},
            {1,0,3,3,3,0,0,0,0,2,2,2,0,1,0,1},
            {1,0,3,0,3,0,4,4,0,2,0,2,0,1,0,1},
            {1,0,3,3,3,0,4,4,0,2,2,2,0,1,0,1},
            {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
            {1,0,1,1,1,1,0,0,1,1,1,1,1,1,0,1},
            {1,0,1,0,0,1,0,0,1,0,0,0,0,1,0,1},
            {1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1},
            {1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1}
    };

    // 2D array of Door objects — only non-null where MAP[y][x] == 5
    private final Door[][] doorsMap = new Door[MAP_HEIGHT][MAP_WIDTH];

    // Maximum visible distance for wall rendering (beyond this = full black)
    private static final double MAX_VISIBLE_DIST = 10.0;

    // ─── Texture system ───────────────────────────────────────────────────

    // All wall/floor textures are 64×64 pixels, pre-decoded into int[] arrays
    // for fast pixel-level access during raycasting
    private static final int TEX_SIZE = 64;
    private Bitmap[] wallTextures;      // Index by texture ID (1-5)
    private int[][] texPixels;          // Pre-extracted pixel arrays for each texture

    // ─── Render quality / performance ─────────────────────────────────────

    private int graphicsQuality = 1;        // 0=Low(240p), 1=Medium(300p), 2=High(480p)
    private volatile boolean pendingQualityChange = false;
    private Bitmap renderBuffer;            // Off-screen bitmap we draw into
    private int[] frameBuffer;              // Raw pixel buffer for the current frame

    // ─── Input / controls ─────────────────────────────────────────────────

    private float sensitivity = 1.0f;
    private boolean showMinimap = true;
    private boolean showPerformance = true;

    // Virtual joystick state (left side of screen)
    private float joyCenterX = 0;
    private float joyCenterY = 0;
    private final float joyRadius = 120;          // Max joystick travel
    private final float joyStickRadius = 50;       // Visual knob radius
    private float joyStickX = 0;
    private float joyStickY = 0;
    private boolean joyActive = false;
    private int joyPointerId = -1;

    // Rotation touch tracking (right side of screen)
    private float lastTouchX;
    private int rotPointerId = -1;

    // Movement speed in tiles per second
    private final double MOVE_SPEED = 4.0;

    // ─── Weapon system state ──────────────────────────────────────────────

    private final List<Weapon> weapons = new ArrayList<>();
    private int currentWeaponIndex = 0;
    private boolean isShooting = false;
    private float shootAnimTime = 0.0f;
    private final float SHOOT_ANIM_DURATION = 0.25f;   // Duration of the muzzle-flash animation
    private float cooldownTimer = 0.0f;
    private float recoilOffsetY = 0.0f;   // Vertical kick-back during firing
    private float recoilOffsetX = 0.0f;   // Random horizontal shake during firing

    // ─── Paint objects (pre-allocated to avoid creating them every frame) ─

    private final Paint hudPaint = new Paint();
    private final Paint joyBgPaint = new Paint();
    private final Paint joyStickPaint = new Paint();
    private final Paint weaponPaint = new Paint();
    private final Paint miniMapPaint = new Paint();

    private final Rect weaponSrcRect = new Rect();
    private final RectF weaponDstRect = new RectF();
    private final Rect renderDstRect = new Rect();
    private final Random random = new Random();

    // Cached text values for the performance overlay (updated once per second)
    private String cachedFpsText = "FPS: 0.0";
    private String cachedRamText = "RAM: 0.0 MB";
    private long lastPerfUpdate = 0;

    // Set by MainActivity when the FIRE button is held down
    private volatile boolean isFiringPressed = false;

    // ─── Constructors ─────────────────────────────────────────────────────

    public GameView(Context context) {
        super(context);
        init();
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    // Called from MainActivity when the FIRE button state changes
    public void setFiringPressed(boolean pressed) {
        this.isFiringPressed = pressed;
    }

    // ─── Initialisation ───────────────────────────────────────────────────

    /**
     * Sets up paint styles, scans the map for door tiles, loads textures
     * and initialises the weapon list.
     */
    private void init() {
        surfaceHolder = getHolder();

        // HUD text — green with a black shadow for readability against any background
        hudPaint.setColor(Color.GREEN);
        hudPaint.setTextSize(36);
        hudPaint.setAntiAlias(true);
        hudPaint.setShadowLayer(4, 2, 2, Color.BLACK);

        // Virtual joystick visuals
        joyBgPaint.setColor(Color.argb(80, 255, 255, 255));
        joyBgPaint.setStyle(Paint.Style.FILL);

        joyStickPaint.setColor(Color.argb(160, 0, 150, 255));
        joyStickPaint.setStyle(Paint.Style.FILL);

        miniMapPaint.setStyle(Paint.Style.FILL);

        // Find all door tiles in the static map and create Door objects for them
        for (int y = 0; y < MAP_HEIGHT; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                if (MAP[y][x] == 5) {
                    doorsMap[y][x] = new Door(x, y);
                }
            }
        }

        loadTextures();
        initWeapons();
    }

    // ─── Texture helpers ──────────────────────────────────────────────────

    /**
     * Strips pure-white pixels (RGB > 235) from a bitmap by making them transparent.
     * Used for weapon sprites so only the gun is visible, not the white background.
     */
    private Bitmap makeWhiteTransparent(Bitmap src) {
        if (src == null) return null;
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, true);
        int width = copy.getWidth();
        int height = copy.getHeight();
        int[] pixels = new int[width * height];
        copy.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            // If all channels are near-white, make it transparent
            if (r > 235 && g > 235 && b > 235) {
                pixels[i] = Color.TRANSPARENT;
            }
        }
        copy.setPixels(pixels, 0, width, 0, 0, width, height);
        return copy;
    }

    /**
     * Loads 5 wall textures from drawable resources, scales them to 64×64,
     * and pre-extracts their pixel arrays for fast lookup during rendering.
     */
    private void loadTextures() {
        wallTextures = new Bitmap[6];
        texPixels = new int[6][TEX_SIZE * TEX_SIZE];

        // Index 0 is unused (empty tile), 1-5 map to wall types
        wallTextures[1] = loadAndScaleTexture(R.drawable.wall_brick);
        wallTextures[2] = loadAndScaleTexture(R.drawable.wall_stone);
        wallTextures[3] = loadAndScaleTexture(R.drawable.wall_wood);
        wallTextures[4] = loadAndScaleTexture(R.drawable.wall_tiles);
        wallTextures[5] = loadAndScaleTexture(R.drawable.wall_door);

        for (int i = 1; i <= 5; i++) {
            if (wallTextures[i] != null) {
                wallTextures[i].getPixels(texPixels[i], 0, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE);
            }
        }
    }

    /**
     * Loads a drawable resource, scales it to TEX_SIZE×TEX_SIZE (64×64).
     * If the resource is missing or fails to load, generates a procedural
     * fallback texture so the game doesn't crash.
     */
    private Bitmap loadAndScaleTexture(int resId) {
        Bitmap original = null;
        try {
            original = BitmapFactory.decodeResource(getResources(), resId);
        } catch (Exception ignored) {}

        if (original == null) {
            // Generate a simple procedural texture as fallback
            Bitmap fallback = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < TEX_SIZE; x++) {
                for (int y = 0; y < TEX_SIZE; y++) {
                    int c = Color.GRAY;
                    if (resId == R.drawable.wall_brick) c = (x % 16 == 0 || y % 16 == 0) ? Color.DKGRAY : Color.RED;
                    else if (resId == R.drawable.wall_stone) c = ((x + y) % 8 == 0) ? Color.BLACK : Color.GREEN;
                    else if (resId == R.drawable.wall_wood) c = Color.rgb(x * 4, y * 4, 200);
                    else if (resId == R.drawable.wall_tiles) c = (x == 0 || y == 0 || x == 63 || y == 63) ? Color.WHITE : Color.YELLOW;
                    else if (resId == R.drawable.wall_door) c = (x < 4 || x > 60 || y < 4 || y > 60) ? Color.DKGRAY : Color.LTGRAY;
                    fallback.setPixel(x, y, c);
                }
            }
            return fallback;
        }
        return Bitmap.createScaledBitmap(original, TEX_SIZE, TEX_SIZE, false);
    }

    // ─── Weapon initialisation ────────────────────────────────────────────

    /**
     * Loads the weapons spritesheet (fps_weapons.png) and slices it into
     * individual weapon sprites. The spritesheet is expected to be:
     *   5 columns (animation frames) × 4 rows (weapons).
     *
     * Each weapon gets an "idle" sprite and a "firing" sprite.
     * If the spritesheet is missing, a placeholder gun is drawn procedurally.
     */
    private void initWeapons() {
        weapons.clear();
        Bitmap spriteSheet = null;

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;  // Don't scale — we need exact pixel dimensions
            spriteSheet = BitmapFactory.decodeResource(getResources(), R.drawable.fps_weapons, options);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (spriteSheet != null) {
            // Make the white background transparent
            spriteSheet = makeWhiteTransparent(spriteSheet);

            int frameW = spriteSheet.getWidth() / 5;
            int frameH = spriteSheet.getHeight() / 4;

            // Row 0: Knife
            Bitmap knifeIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 0, frameW, frameH);
            Bitmap knifeFire = Bitmap.createBitmap(spriteSheet, frameW * 3, frameH * 0, frameW, frameH);
            Weapon knife = new Weapon("Knife", 999, 999, 15, 0.40f);
            knife.idleSprite = knifeIdle;
            knife.fireSprite = knifeFire;
            weapons.add(knife);

            // Row 1: Pistol
            Bitmap pistolIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 1, frameW, frameH);
            Bitmap pistolFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 1, frameW, frameH);
            Weapon pistol = new Weapon("Pistol", 50, 100, 25, 0.35f);
            pistol.idleSprite = pistolIdle;
            pistol.fireSprite = pistolFire;
            weapons.add(pistol);

            // Row 2: Machine Gun
            Bitmap mgIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 2, frameW, frameH);
            Bitmap mgFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 2, frameW, frameH);
            Weapon machineGun = new Weapon("Machine Gun", 100, 200, 20, 0.15f);
            machineGun.idleSprite = mgIdle;
            machineGun.fireSprite = mgFire;
            weapons.add(machineGun);

            // Row 3: Chain Gun
            Bitmap cgIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 3, frameW, frameH);
            Bitmap cgFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 3, frameW, frameH);
            Weapon chainGun = new Weapon("Chain Gun", 150, 300, 18, 0.08f);
            chainGun.idleSprite = cgIdle;
            chainGun.fireSprite = cgFire;
            weapons.add(chainGun);

        } else {
            // Fallback: create a simple procedural gun sprite
            Weapon knife = new Weapon("Knife", 999, 999, 15, 0.40f);
            knife.idleSprite = createPlaceholderGunSprite(Color.GRAY, false);
            knife.fireSprite = createPlaceholderGunSprite(Color.WHITE, true);
            weapons.add(knife);
        }
    }

    /**
     * Creates a simple placeholder gun sprite using Canvas drawing.
     * Used when the actual spritesheet resource is missing.
     */
    private Bitmap createPlaceholderGunSprite(int barrelColor, boolean isFiring) {
        int w = 120, h = 140;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();

        p.setColor(barrelColor);
        c.drawRect(40, 40, 80, 140, p);

        p.setColor(Color.rgb(100, 50, 0));
        c.drawRect(45, 90, 75, 140, p);

        // Draw a muzzle flash if the weapon is firing
        if (isFiring) {
            p.setColor(Color.YELLOW);
            c.drawCircle(60, 25, 25, p);
            p.setColor(Color.WHITE);
            c.drawCircle(60, 25, 12, p);
        }
        return bmp;
    }

    // ─── Weapon actions ───────────────────────────────────────────────────

    /** Switches to the weapon at the given index (0=Knife, 1=Pistol, 2=MG, 3=CG). */
    public void selectWeapon(int index) {
        if (index >= 0 && index < weapons.size()) {
            currentWeaponIndex = index;
        }
    }

    /**
     * Fires the current weapon if it's off cooldown and has ammo.
     * Knife (index 0) does not consume ammo.
     * Triggers the shoot animation, starts the cooldown, and performs hitscan.
     */
    public void shootCurrentWeapon() {
        if (weapons.isEmpty()) return;
        Weapon w = weapons.get(currentWeaponIndex);

        if (cooldownTimer <= 0 && w.ammo > 0) {
            if (currentWeaponIndex != 0) {
                w.ammo--;
            }
            isShooting = true;
            shootAnimTime = SHOOT_ANIM_DURATION;
            cooldownTimer = w.fireRate;
            performHitscan();
        }
    }

    /**
     * Simple hitscan — casts a ray forward from the player's position
     * and checks if it hits a wall within 12 tiles.
     * (Currently unused for damage, but the foundation is here for enemies later.)
     */
    private void performHitscan() {
        double rayX = posX;
        double rayY = posY;
        double stepSize = 0.1;

        for (double d = 0; d < 12.0; d += stepSize) {
            rayX += dirX * stepSize;
            rayY += dirY * stepSize;

            int mapX = (int) rayX;
            int mapY = (int) rayY;

            if (mapX >= 0 && mapX < MAP_WIDTH && mapY >= 0 && mapY < MAP_HEIGHT) {
                if (MAP[mapY][mapX] > 0 && MAP[mapY][mapX] != 5) {
                    break;
                }
            }
        }
    }

    /**
     * Checks the tile directly in front of the player (1.5 tiles ahead),
     * and if it's a door, toggles it open/closed.
     * Called from the USE button.
     */
    public void interactWithDoor() {
        int checkX = (int) (posX + dirX * 1.5);
        int checkY = (int) (posY + dirY * 1.5);

        if (checkX >= 0 && checkX < MAP_WIDTH && checkY >= 0 && checkY < MAP_HEIGHT) {
            if (MAP[checkY][checkX] == 5 && doorsMap[checkY][checkX] != null) {
                doorsMap[checkY][checkX].toggle();
            }
        }
    }

    // ─── Settings setters / getters ───────────────────────────────────────

    public void setGraphicsQuality(int quality) {
        this.graphicsQuality = quality;
        this.pendingQualityChange = true;  // Triggers render buffer recreation
    }

    public int getGraphicsQuality() { return this.graphicsQuality; }
    public void setSensitivity(float sensitivity) { this.sensitivity = sensitivity; }
    public float getSensitivity() { return this.sensitivity; }
    public void setShowMinimap(boolean show) { this.showMinimap = show; }
    public boolean isShowMinimap() { return this.showMinimap; }
    public void setShowPerformance(boolean show) { this.showPerformance = show; }
    public boolean isShowPerformance() { return this.showPerformance; }

    // ─── Lifecycle ────────────────────────────────────────────────────────

    /** Starts or resumes the game loop thread. */
    public void resume() {
        running = true;
        lastTime = System.nanoTime();
        gameThread = new Thread(this);
        gameThread.start();
    }

    /** Stops the game loop and waits for the thread to terminate. */
    public void pause() {
        running = false;
        try {
            if (gameThread != null) {
                gameThread.join();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // ─── Touch input ──────────────────────────────────────────────────────

    /**
     * Multi-touch handler:
     *  - Left half of screen → virtual joystick (movement + strafe)
     *  - Right half of screen → horizontal swipe to rotate the camera
     *
     * Uses pointer IDs to track multiple fingers independently.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);

        float x = event.getX(index);
        float y = event.getY(index);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                // Left side → joystick (only if not already active)
                if (x < getWidth() / 2.0f && !joyActive) {
                    joyActive = true;
                    joyPointerId = pointerId;
                    joyCenterX = x;
                    joyCenterY = y;
                    joyStickX = x;
                    joyStickY = y;
                }
                // Right side → rotation control (only one finger at a time)
                else if (x >= getWidth() / 2.0f && rotPointerId == -1) {
                    rotPointerId = pointerId;
                    lastTouchX = x;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int activeId = event.getPointerId(i);
                    float ax = event.getX(i);
                    float ay = event.getY(i);

                    // Update virtual joystick position (clamped within the radius circle)
                    if (activeId == joyPointerId && joyActive) {
                        float dx = ax - joyCenterX;
                        float dy = ay - joyCenterY;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);

                        if (dist <= joyRadius) {
                            joyStickX = ax;
                            joyStickY = ay;
                        } else {
                            // Clamp the stick to the edge of the circle
                            joyStickX = joyCenterX + (dx / dist) * joyRadius;
                            joyStickY = joyCenterY + (dy / dist) * joyRadius;
                        }
                    }
                    // Track horizontal finger movement for camera rotation
                    else if (activeId == rotPointerId) {
                        float dx = ax - lastTouchX;
                        double angle = dx * 0.003f * sensitivity;
                        rotatePlayer(angle);
                        lastTouchX = ax;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                // Release the joystick (return to center)
                if (pointerId == joyPointerId) {
                    joyActive = false;
                    joyPointerId = -1;
                    joyStickX = joyCenterX;
                    joyStickY = joyCenterY;
                }
                // Release rotation finger
                else if (pointerId == rotPointerId) {
                    rotPointerId = -1;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                // Everything resets if the touch sequence is cancelled
                joyActive = false;
                joyPointerId = -1;
                rotPointerId = -1;
                joyStickX = joyCenterX;
                joyStickY = joyCenterY;
                break;
        }
        return true;
    }

    // ─── Player movement helpers ──────────────────────────────────────────

    /**
     * Rotates the player's direction vector by the given angle (radians).
     * Standard 2D rotation matrix:
     *   dirX' = dirX*cos(a) - dirY*sin(a)
     *   dirY' = dirX*sin(a) + dirY*cos(a)
     */
    private void rotatePlayer(double angle) {
        double oldDirX = dirX;
        dirX = dirX * Math.cos(angle) - dirY * Math.sin(angle);
        dirY = oldDirX * Math.sin(angle) + dirY * Math.cos(angle);
    }

    // ─── Door logic ───────────────────────────────────────────────────────

    /**
     * Updates all doors' state machines every frame.
     * Each door progresses through: CLOSED → OPENING → OPEN → CLOSING → CLOSED.
     * A door auto-closes after holdTime seconds, unless the player is still inside.
     */
    private void updateDoors(double deltaTime) {
        float dt = (float) deltaTime;

        for (int y = 0; y < MAP_HEIGHT; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                Door door = doorsMap[y][x];
                if (door == null) continue;

                switch (door.state) {
                    case OPENING:
                        // Slide the door open over time
                        door.offset += door.openSpeed * dt;
                        if (door.offset >= 1.0f) {
                            door.offset = 1.0f;
                            door.state = DoorState.OPEN;
                            door.timer = door.holdTime;
                        }
                        break;

                    case OPEN:
                        // Countdown before auto-closing
                        door.timer -= dt;
                        if (door.timer <= 0) {
                            // Don't close if the player is standing in the doorway
                            if (!isPlayerInsideTile(door.x, door.y)) {
                                door.state = DoorState.CLOSING;
                            } else {
                                door.timer = 1.0f; // Extend delay
                            }
                        }
                        break;

                    case CLOSING:
                        // If player walks into the closing door, re-open it
                        if (isPlayerInsideTile(door.x, door.y)) {
                            door.state = DoorState.OPENING;
                            break;
                        }

                        door.offset -= door.openSpeed * dt;
                        if (door.offset <= 0.0f) {
                            door.offset = 0.0f;
                            door.state = DoorState.CLOSED;
                        }
                        break;

                    case CLOSED:
                        break;
                }
            }
        }
    }

    /**
     * Returns true if the player's bounding box overlaps the given map tile.
     * Uses a small radius (0.3 tiles) to avoid clipping through walls.
     */
    private boolean isPlayerInsideTile(int tileX, int tileY) {
        double radius = 0.3;
        return (posX + radius > tileX && posX - radius < tileX + 1.0) &&
                (posY + radius > tileY && posY - radius < tileY + 1.0);
    }

    /**
     * Collision detection for a target position.
     * Returns true if the target tile is walkable (empty, or an open door).
     * Walls (tiles 1-4) block movement entirely.
     */
    private boolean canMoveTo(double targetX, double targetY) {
        int tileX = (int) Math.floor(targetX);
        int tileY = (int) Math.floor(targetY);

        if (tileX < 0 || tileX >= MAP_WIDTH || tileY < 0 || tileY >= MAP_HEIGHT) return false;

        int tile = MAP[tileY][tileX];
        if (tile == 0) return true;

        // Door tiles are walkable if the door is at least 80% open (gap is wide enough)
        if (tile == 5) {
            Door door = doorsMap[tileY][tileX];
            return door != null && door.offset >= 0.8f;
        }

        return false;
    }

    // ─── Weapon animation ─────────────────────────────────────────────────

    /**
     * Handles the cooldown timer and shoot animation (recoil + muzzle flash).
     * Recoil uses a sine wave: the gun kicks back and settles over the animation duration.
     * Random horizontal shake adds a bit of "oomph" to the firing feel.
     */
    private void updateWeaponAnimation(float dt) {
        if (cooldownTimer > 0) {
            cooldownTimer -= dt;
        }

        if (isShooting) {
            shootAnimTime -= dt;

            float progress = Math.max(0.0f, Math.min(1.0f, 1.0f - (shootAnimTime / SHOOT_ANIM_DURATION)));
            float recoilFactor = (float) Math.sin(progress * Math.PI); // Smooth bell curve

            recoilOffsetY = recoilFactor * 45.0f;
            recoilOffsetX = (float) ((random.nextFloat() - 0.5f) * recoilFactor * 10.0f);

            if (shootAnimTime <= 0) {
                isShooting = false;
                recoilOffsetY = 0;
                recoilOffsetX = 0;
            }
        }
    }

    // ─── Physics update (called every frame) ──────────────────────────────

    /**
     * Main update step for each frame:
     * 1. Update door animations
     * 2. Update weapon animations
     * 3. Auto-fire if the FIRE button is held
     * 4. Read the virtual joystick and move the player accordingly
     *
     * Movement uses the camera plane to support strafing (moving sideways).
     * Collision is checked separately on X and Y axes (wall sliding).
     */
    private void updatePhysics(double deltaTime) {
        updateDoors(deltaTime);
        updateWeaponAnimation((float) deltaTime);

        if (isFiringPressed) {
            shootCurrentWeapon();
        }

        if (joyActive) {
            float dx = joyStickX - joyCenterX;
            float dy = joyStickY - joyCenterY;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            // Dead zone — ignore very small movements (touch jitter)
            if (dist > 10) {
                // Joystick Y is inverted: pulling down = move forward
                double forwardScale = -dy / joyRadius;
                double strafeScale = dx / joyRadius;

                double moveX = (dirX * forwardScale + planeX * strafeScale) * MOVE_SPEED * deltaTime;
                double moveY = (dirY * forwardScale + planeY * strafeScale) * MOVE_SPEED * deltaTime;

                // Padding around the player to prevent wall-hugging/clipping
                double padding = 0.3;

                // Check X and Y separately so sliding along walls feels smooth
                if (canMoveTo(posX + moveX + Math.signum(moveX) * padding, posY)) {
                    posX += moveX;
                }
                if (canMoveTo(posX, posY + moveY + Math.signum(moveY) * padding)) {
                    posY += moveY;
                }
            }
        }
    }

    // ─── Game loop (Runnable.run) ─────────────────────────────────────────

    /**
     * The main game loop.
     * Each iteration:
     *  - Calculates delta-time for frame-rate-independent physics
     *  - Updates FPS estimate (exponential moving average)
     *  - Refreshes performance overlay text once per second
     *  - Steps the physics simulation
     *  - Locks the Surface and renders the current frame
     */
    @Override
    public void run() {
        while (running) {
            if (!surfaceHolder.getSurface().isValid()) continue;

            // ── Delta-time calculation ────────────────────────────────
            long now = System.nanoTime();
            double deltaTime = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            if (deltaTime > 0) {
                float currentFps = (float) (1.0 / deltaTime);
                fps = fps * 0.95f + currentFps * 0.05f; // SMA smoothing
            }

            // ── Update performance text (once per second) ─────────────
            if (now - lastPerfUpdate > 1_000_000_000L) {
                Runtime runtime = Runtime.getRuntime();
                ramUsageBytes = runtime.totalMemory() - runtime.freeMemory();
                cachedFpsText = String.format("FPS: %.1f", fps);
                cachedRamText = String.format("RAM: %.1f MB", ramUsageBytes / (1024f * 1024f));
                lastPerfUpdate = now;
            }

            // ── Physics ───────────────────────────────────────────────
            updatePhysics(deltaTime);

            // ── Render ────────────────────────────────────────────────
            Canvas canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                try {
                    renderGame(canvas);
                } finally {
                    surfaceHolder.unlockCanvasAndPost(canvas);
                }
            }
        }
    }

    // ─── Rendering ────────────────────────────────────────────────────────

    /**
     * The main rendering function for one frame.
     *
     * Steps:
     * 1. Calculate the camera plane (adjusted for screen aspect ratio)
     * 2. Determine the internal render resolution (240/300/480 lines)
     * 3. Fill the framebuffer with ceiling and floor colours
     * 4. Cast one ray per screen column (DDA algorithm)
     * 5. For each ray hit, draw a textured wall strip with distance shading
     * 6. Blit the framebuffer to the screen (scaled up to full resolution)
     * 7. Draw the virtual joystick overlay
     * 8. Draw the weapon sprite on top
     * 9. Optionally draw the minimap and FPS/RAM overlay
     */
    private void renderGame(Canvas canvas) {
        int screenW = canvas.getWidth();
        int screenH = canvas.getHeight();

        float screenAspect = (float) screenW / screenH;

        // Adjust the camera plane length to maintain FOV across different aspect ratios
        double planeLength = 0.66 * (screenAspect / (4.0 / 3.0));
        planeX = -dirY * planeLength;
        planeY = dirX * planeLength;

        // ── Render buffer resolution (quality setting) ────────────────
        int targetH = 300;
        if (graphicsQuality == 0) targetH = 240;
        else if (graphicsQuality == 1) targetH = 300;
        else if (graphicsQuality == 2) targetH = 480;

        int targetW = Math.round(targetH * screenAspect);

        // Recreate the buffer if resolution changed
        if (renderBuffer == null || renderBuffer.getWidth() != targetW || renderBuffer.getHeight() != targetH || pendingQualityChange) {
            renderBuffer = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            frameBuffer = new int[targetW * targetH];
            pendingQualityChange = false;
        }

        // ── Sky / floor fill ──────────────────────────────────────────
        int ceilColor = Color.rgb(20, 20, 30);
        int floorColor = Color.rgb(40, 40, 40);
        int halfSize = (targetW * targetH) / 2;
        Arrays.fill(frameBuffer, 0, halfSize, ceilColor);
        Arrays.fill(frameBuffer, halfSize, targetW * targetH, floorColor);

        // ── Raycasting: one vertical strip per screen column ──────────
        for (int x = 0; x < targetW; x++) {
            // cameraX maps the screen coordinate to the camera plane (-1..+1)
            double cameraX = 2 * x / (double) targetW - 1;
            double rayDirX = dirX + planeX * cameraX;
            double rayDirY = dirY + planeY * cameraX;

            // Map position of the ray's origin (integer tile coords)
            int mapX = (int) posX;
            int mapY = (int) posY;

            // DDA (Digital Differential Analyzer) initialisation
            double sideDistX, sideDistY;
            double deltaDistX = (rayDirX == 0) ? Double.MAX_VALUE : Math.abs(1 / rayDirX);
            double deltaDistY = (rayDirY == 0) ? Double.MAX_VALUE : Math.abs(1 / rayDirY);
            double perpWallDist = 0.0;

            int stepX, stepY;
            int hit = 0;
            int side = 0; // 0 = X-side wall, 1 = Y-side wall
            double wallX = 0.0;

            // Calculate step direction and initial sideDist
            if (rayDirX < 0) {
                stepX = -1;
                sideDistX = (posX - mapX) * deltaDistX;
            } else {
                stepX = 1;
                sideDistX = (mapX + 1.0 - posX) * deltaDistX;
            }
            if (rayDirY < 0) {
                stepY = -1;
                sideDistY = (posY - mapY) * deltaDistY;
            } else {
                stepY = 1;
                sideDistY = (mapY + 1.0 - posY) * deltaDistY;
            }

            // DDA loop: step through the map grid until we hit a wall or go out of bounds
            while (hit == 0) {
                if (sideDistX < sideDistY) {
                    sideDistX += deltaDistX;
                    mapX += stepX;
                    side = 0;
                } else {
                    sideDistY += deltaDistY;
                    mapY += stepY;
                    side = 1;
                }

                if (mapX < 0 || mapX >= MAP_WIDTH || mapY < 0 || mapY >= MAP_HEIGHT) {
                    hit = -1; // Out of bounds — skip this column
                    break;
                }

                int tile = MAP[mapY][mapX];

                if (tile > 0) {
                    hit = tile;

                    // Calculate perpendicular distance (avoids fisheye effect)
                    if (side == 0) perpWallDist = (sideDistX - deltaDistX);
                    else          perpWallDist = (sideDistY - deltaDistY);

                    // wallX is the exact point on the wall where the ray hit (0..1)
                    if (side == 0) wallX = posY + perpWallDist * rayDirY;
                    else          wallX = posX + perpWallDist * rayDirX;
                    wallX -= Math.floor(wallX);

                    // If this is a door tile, check if the ray passed through the open gap
                    if (tile == 5) {
                        Door door = doorsMap[mapY][mapX];
                        float doorOffset = (door != null) ? door.offset : 0.0f;

                        // If the impact point is within the open gap, ignore the hit
                        if (wallX < doorOffset) {
                            hit = 0;
                            wallX = 0.0;
                        } else {
                            wallX -= doorOffset;
                        }
                    }
                }
            }

            if (hit <= 0) continue; // No wall hit — leave black

            if (perpWallDist <= 0) perpWallDist = 0.01;

            // Calculate the vertical strip height on screen
            int lineHeight = (int) (targetH / perpWallDist);
            int drawStart = -lineHeight / 2 + targetH / 2;
            int drawEnd = lineHeight / 2 + targetH / 2;

            // Clamp to screen bounds
            int actualDrawStart = Math.max(0, drawStart);
            int actualDrawEnd = Math.min(targetH - 1, drawEnd);

            // Which column of the texture to draw (based on wallX)
            int texX = (int) (wallX * (double) TEX_SIZE);

            // Flip texture horizontally depending on wall direction for consistency
            if (side == 0 && rayDirX > 0) texX = TEX_SIZE - texX - 1;
            if (side == 1 && rayDirY < 0) texX = TEX_SIZE - texX - 1;

            texX &= (TEX_SIZE - 1); // Clamp to texture size

            // Step along the texture Y axis per screen pixel
            double stepYTex = 1.0 * TEX_SIZE / lineHeight;
            double texPos = (actualDrawStart - targetH / 2.0 + lineHeight / 2.0) * stepYTex;

            // Clamp texture index to valid range (1-5)
            int textureIndex = Math.min(hit, 5);

            // Distance-based darkening: farther walls are darker
            double brightness = 1.0 - (perpWallDist / MAX_VISIBLE_DIST);
            if (brightness < 0.0) brightness = 0.0;
            if (brightness > 1.0) brightness = 1.0;

            // Y-side walls are slightly darker for a subtle lighting effect
            if (side == 1) {
                brightness *= 0.75;
            }

            int shadeScale = (int) (brightness * 256);

            // Draw this vertical strip pixel-by-pixel into the framebuffer
            for (int y = actualDrawStart; y <= actualDrawEnd; y++) {
                int texY = (int) texPos & (TEX_SIZE - 1);
                texPos += stepYTex;

                int rawColor = texPixels[textureIndex][TEX_SIZE * texY + texX];

                // Apply distance-based shading (multiply each channel)
                int r = (((rawColor >> 16) & 0xFF) * shadeScale) >> 8;
                int g = (((rawColor >> 8) & 0xFF) * shadeScale) >> 8;
                int b = ((rawColor & 0xFF) * shadeScale) >> 8;

                frameBuffer[y * targetW + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        // Copy the pixel buffer to the Bitmap, then draw it scaled to full screen
        renderBuffer.setPixels(frameBuffer, 0, targetW, 0, 0, targetW, targetH);

        renderDstRect.set(0, 0, screenW, screenH);
        canvas.drawBitmap(renderBuffer, null, renderDstRect, null);

        // ── Virtual joystick ──────────────────────────────────────────
        // Initialise joystick centre position on first render (screen size known now)
        if (joyCenterY == 0) {
            joyCenterX = screenW * 0.15f;
            joyCenterY = screenH * 0.72f;
            joyStickX = joyCenterX;
            joyStickY = joyCenterY;
        }

        canvas.drawCircle(joyCenterX, joyCenterY, joyRadius, joyBgPaint);
        canvas.drawCircle(joyStickX, joyStickY, joyStickRadius, joyStickPaint);

        // ── Overlays ──────────────────────────────────────────────────
        drawWeapon(canvas, screenW, screenH);
        drawHUD(canvas, screenH);

        if (showMinimap) drawMiniMap(canvas, screenW);
        if (showPerformance) drawPerformanceOverlay(canvas);
    }

    // ─── HUD / weapon sprite ──────────────────────────────────────────────

    /**
     * Draws the current weapon sprite at the bottom-center of the screen.
     * If the weapon is firing, shows the fire sprite (muzzle flash).
     * Applies recoil offsets for a responsive feel.
     */
    private void drawWeapon(Canvas canvas, int screenW, int screenH) {
        if (weapons.isEmpty()) return;
        Weapon w = weapons.get(currentWeaponIndex);

        Bitmap gunSprite = (isShooting && shootAnimTime > 0) ? w.fireSprite : w.idleSprite;
        if (gunSprite == null) return;

        // Scale the weapon relative to screen height
        int gunWidth = (int) (screenH * 0.55f);
        int gunHeight = (int) (gunWidth * ((float) gunSprite.getHeight() / gunSprite.getWidth()));

        float left = (screenW - gunWidth) / 2.0f + recoilOffsetX;
        float top = screenH - gunHeight + recoilOffsetY;

        weaponSrcRect.set(0, 0, gunSprite.getWidth(), gunSprite.getHeight());
        weaponDstRect.set(left, top, left + gunWidth, top + gunHeight);

        weaponPaint.setFilterBitmap(false); // Nearest-neighbour scaling (pixel-art look)
        canvas.drawBitmap(gunSprite, weaponSrcRect, weaponDstRect, weaponPaint);
    }

    /**
     * Draws the weapon name and ammo counter at the bottom-left of the screen.
     */
    private void drawHUD(Canvas canvas, int screenH) {
        if (weapons.isEmpty()) return;
        Weapon w = weapons.get(currentWeaponIndex);

        hudPaint.setColor(Color.YELLOW);
        hudPaint.setTextSize(36);

        canvas.drawText("GUN: " + w.name, 30, screenH - 50, hudPaint);
        canvas.drawText("AMMO: " + w.ammo + "/" + w.maxAmmo, 30, screenH - 15, hudPaint);
    }

    // ─── Minimap ──────────────────────────────────────────────────────────

    /**
     * Draws a top-down minimap in the top-right corner.
     * Each map tile is an 8×8 pixel square.
     * The player is shown as a yellow dot, with a red line indicating facing direction.
     * Doors change colour (cyan → blue) as they open.
     */
    private void drawMiniMap(Canvas canvas, int screenW) {
        float size = 8f;
        float mapSize = MAP_WIDTH * size;
        float padding = 20f;
        float startX = screenW - mapSize - padding;
        float startY = padding;

        for (int y = 0; y < MAP_HEIGHT; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                int wallType = MAP[y][x];

                // Colour code: open doors are blue, closed doors are cyan, walls are grey, floors are dark
                if (wallType == 5) {
                    Door d = doorsMap[y][x];
                    miniMapPaint.setColor(d != null && d.offset > 0.5f ? Color.BLUE : Color.CYAN);
                } else {
                    miniMapPaint.setColor(wallType > 0 ? Color.argb(160, 100, 100, 100) : Color.argb(40, 20, 20, 20));
                }

                float left = startX + x * size;
                float top = startY + y * size;
                canvas.drawRect(left, top, left + size - 1, top + size - 1, miniMapPaint);
            }
        }

        // Player dot
        miniMapPaint.setColor(Color.YELLOW);
        float playerX = startX + (float) posX * size;
        float playerY = startY + (float) posY * size;
        canvas.drawCircle(playerX, playerY, 4f, miniMapPaint);

        // Direction indicator line
        miniMapPaint.setColor(Color.RED);
        miniMapPaint.setStrokeWidth(2f);
        canvas.drawLine(playerX, playerY, playerX + (float) dirX * 12f, playerY + (float) dirY * 12f, miniMapPaint);
    }

    // ─── Performance overlay ──────────────────────────────────────────────

    /**
     * Draws the FPS counter and RAM usage text in the top-left corner.
     * Text is updated once per second (cached) to avoid excessive string allocation.
     */
    private void drawPerformanceOverlay(Canvas canvas) {
        hudPaint.setColor(Color.GREEN);
        hudPaint.setTextSize(32);
        canvas.drawText(cachedFpsText, 30, 45, hudPaint);
        canvas.drawText(cachedRamText, 30, 85, hudPaint);
    }
}