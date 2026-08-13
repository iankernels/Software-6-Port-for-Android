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

public class GameView extends SurfaceView implements Runnable {

    public enum DoorState { CLOSED, OPENING, OPEN, CLOSING }

    public static class Door {
        public int x, y;
        public float offset = 0.0f;
        public DoorState state = DoorState.CLOSED;
        public float timer = 0.0f;
        public float openSpeed = 1.0f;
        public float holdTime = 3.0f;

        public Door(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public void toggle() {
            if (state == DoorState.CLOSED || state == DoorState.CLOSING) {
                state = DoorState.OPENING;
            } else if (state == DoorState.OPEN || state == DoorState.OPENING) {
                state = DoorState.CLOSING;
            }
        }
    }

    public static class Weapon {
        public String name;
        public int ammo;
        public int maxAmmo;
        public int damage;
        public float fireRate;
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

    private Thread gameThread = null;
    private SurfaceHolder surfaceHolder;
    private volatile boolean running = false;

    private long lastTime;
    private float fps;
    private long ramUsageBytes;

    private double posX = 3.5, posY = 3.5;
    private double dirX = 1.0, dirY = 0.0;
    private double planeX = 0.0, planeY = 0.66;

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

    private final Door[][] doorsMap = new Door[MAP_HEIGHT][MAP_WIDTH];
    private static final double MAX_VISIBLE_DIST = 10.0;
    private static final int TEX_SIZE = 64;
    private Bitmap[] wallTextures;
    private int[][] texPixels;

    private int graphicsQuality = 1;
    private volatile boolean pendingQualityChange = false;
    private Bitmap renderBuffer;
    private int[] frameBuffer;

    private float sensitivity = 1.0f;
    private boolean showMinimap = true;
    private boolean showPerformance = true;

    private float joyCenterX = 0;
    private float joyCenterY = 0;
    private final float joyRadius = 120;
    private final float joyStickRadius = 50;
    private float joyStickX = 0;
    private float joyStickY = 0;
    private boolean joyActive = false;
    private int joyPointerId = -1;

    private float lastTouchX;
    private int rotPointerId = -1;

    private final double MOVE_SPEED = 4.0;

    private final List<Weapon> weapons = new ArrayList<>();
    private int currentWeaponIndex = 0;
    private boolean isShooting = false;
    private float shootAnimTime = 0.0f;
    private final float SHOOT_ANIM_DURATION = 0.25f;
    private float cooldownTimer = 0.0f;
    private float recoilOffsetY = 0.0f;
    private float recoilOffsetX = 0.0f;

    private final Paint hudPaint = new Paint();
    private final Paint joyBgPaint = new Paint();
    private final Paint joyStickPaint = new Paint();
    private final Paint weaponPaint = new Paint();
    private final Paint miniMapPaint = new Paint();

    private final Rect weaponSrcRect = new Rect();
    private final RectF weaponDstRect = new RectF();
    private final Rect renderDstRect = new Rect();
    private final Random random = new Random();

    private String cachedFpsText = "FPS: 0.0";
    private String cachedRamText = "RAM: 0.0 MB";
    private long lastPerfUpdate = 0;

    private volatile boolean isFiringPressed = false;

    public GameView(Context context) {
        super(context);
        init();
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public void setFiringPressed(boolean pressed) {
        this.isFiringPressed = pressed;
    }

    private void init() {
        surfaceHolder = getHolder();

        hudPaint.setColor(Color.GREEN);
        hudPaint.setTextSize(36);
        hudPaint.setAntiAlias(true);
        hudPaint.setShadowLayer(4, 2, 2, Color.BLACK);

        joyBgPaint.setColor(Color.argb(80, 255, 255, 255));
        joyBgPaint.setStyle(Paint.Style.FILL);

        joyStickPaint.setColor(Color.argb(160, 0, 150, 255));
        joyStickPaint.setStyle(Paint.Style.FILL);

        miniMapPaint.setStyle(Paint.Style.FILL);

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

            if (r > 235 && g > 235 && b > 235) {
                pixels[i] = Color.TRANSPARENT;
            }
        }
        copy.setPixels(pixels, 0, width, 0, 0, width, height);
        return copy;
    }

    private void loadTextures() {
        wallTextures = new Bitmap[6];
        texPixels = new int[6][TEX_SIZE * TEX_SIZE];

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

    private Bitmap loadAndScaleTexture(int resId) {
        Bitmap original = null;
        try {
            original = BitmapFactory.decodeResource(getResources(), resId);
        } catch (Exception ignored) {}

        if (original == null) {
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

    private void initWeapons() {
        weapons.clear();
        Bitmap spriteSheet = null;

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            spriteSheet = BitmapFactory.decodeResource(getResources(), R.drawable.fps_weapons, options);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (spriteSheet != null) {
            spriteSheet = makeWhiteTransparent(spriteSheet);

            int frameW = spriteSheet.getWidth() / 5;
            int frameH = spriteSheet.getHeight() / 4;

            Bitmap knifeIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 0, frameW, frameH);
            Bitmap knifeFire = Bitmap.createBitmap(spriteSheet, frameW * 3, frameH * 0, frameW, frameH);
            Weapon knife = new Weapon("Knife", 999, 999, 15, 0.40f);
            knife.idleSprite = knifeIdle;
            knife.fireSprite = knifeFire;
            weapons.add(knife);

            Bitmap pistolIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 1, frameW, frameH);
            Bitmap pistolFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 1, frameW, frameH);
            Weapon pistol = new Weapon("Pistol", 50, 100, 25, 0.35f);
            pistol.idleSprite = pistolIdle;
            pistol.fireSprite = pistolFire;
            weapons.add(pistol);

            Bitmap mgIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 2, frameW, frameH);
            Bitmap mgFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 2, frameW, frameH);
            Weapon machineGun = new Weapon("Machine Gun", 100, 200, 20, 0.15f);
            machineGun.idleSprite = mgIdle;
            machineGun.fireSprite = mgFire;
            weapons.add(machineGun);

            Bitmap cgIdle = Bitmap.createBitmap(spriteSheet, frameW * 1, frameH * 3, frameW, frameH);
            Bitmap cgFire = Bitmap.createBitmap(spriteSheet, frameW * 2, frameH * 3, frameW, frameH);
            Weapon chainGun = new Weapon("Chain Gun", 150, 300, 18, 0.08f);
            chainGun.idleSprite = cgIdle;
            chainGun.fireSprite = cgFire;
            weapons.add(chainGun);

        } else {
            Weapon knife = new Weapon("Knife", 999, 999, 15, 0.40f);
            knife.idleSprite = createPlaceholderGunSprite(Color.GRAY, false);
            knife.fireSprite = createPlaceholderGunSprite(Color.WHITE, true);
            weapons.add(knife);
        }
    }

    private Bitmap createPlaceholderGunSprite(int barrelColor, boolean isFiring) {
        int w = 120, h = 140;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();

        p.setColor(barrelColor);
        c.drawRect(40, 40, 80, 140, p);

        p.setColor(Color.rgb(100, 50, 0));
        c.drawRect(45, 90, 75, 140, p);

        if (isFiring) {
            p.setColor(Color.YELLOW);
            c.drawCircle(60, 25, 25, p);
            p.setColor(Color.WHITE);
            c.drawCircle(60, 25, 12, p);
        }
        return bmp;
    }

    public void selectWeapon(int index) {
        if (index >= 0 && index < weapons.size()) {
            currentWeaponIndex = index;
        }
    }

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

    public void interactWithDoor() {
        int checkX = (int) (posX + dirX * 1.5);
        int checkY = (int) (posY + dirY * 1.5);

        if (checkX >= 0 && checkX < MAP_WIDTH && checkY >= 0 && checkY < MAP_HEIGHT) {
            if (MAP[checkY][checkX] == 5 && doorsMap[checkY][checkX] != null) {
                doorsMap[checkY][checkX].toggle();
            }
        }
    }

    public void setGraphicsQuality(int quality) {
        this.graphicsQuality = quality;
        this.pendingQualityChange = true;
    }

    public int getGraphicsQuality() { return this.graphicsQuality; }
    public void setSensitivity(float sensitivity) { this.sensitivity = sensitivity; }
    public float getSensitivity() { return this.sensitivity; }
    public void setShowMinimap(boolean show) { this.showMinimap = show; }
    public boolean isShowMinimap() { return this.showMinimap; }
    public void setShowPerformance(boolean show) { this.showPerformance = show; }
    public boolean isShowPerformance() { return this.showPerformance; }

    public void resume() {
        running = true;
        lastTime = System.nanoTime();
        gameThread = new Thread(this);
        gameThread.start();
    }

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
                if (x < getWidth() / 2.0f && !joyActive) {
                    joyActive = true;
                    joyPointerId = pointerId;
                    joyCenterX = x;
                    joyCenterY = y;
                    joyStickX = x;
                    joyStickY = y;
                } else if (x >= getWidth() / 2.0f && rotPointerId == -1) {
                    rotPointerId = pointerId;
                    lastTouchX = x;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int activeId = event.getPointerId(i);
                    float ax = event.getX(i);
                    float ay = event.getY(i);

                    if (activeId == joyPointerId && joyActive) {
                        float dx = ax - joyCenterX;
                        float dy = ay - joyCenterY;
                        float dist = (float) Math.sqrt(dx * dx + dy * dy);

                        if (dist <= joyRadius) {
                            joyStickX = ax;
                            joyStickY = ay;
                        } else {
                            joyStickX = joyCenterX + (dx / dist) * joyRadius;
                            joyStickY = joyCenterY + (dy / dist) * joyRadius;
                        }
                    } else if (activeId == rotPointerId) {
                        float dx = ax - lastTouchX;
                        double angle = dx * 0.003f * sensitivity;
                        rotatePlayer(angle);
                        lastTouchX = ax;
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId == joyPointerId) {
                    joyActive = false;
                    joyPointerId = -1;
                    joyStickX = joyCenterX;
                    joyStickY = joyCenterY;
                } else if (pointerId == rotPointerId) {
                    rotPointerId = -1;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                joyActive = false;
                joyPointerId = -1;
                rotPointerId = -1;
                joyStickX = joyCenterX;
                joyStickY = joyCenterY;
                break;
        }
        return true;
    }

    private void rotatePlayer(double angle) {
        double oldDirX = dirX;
        dirX = dirX * Math.cos(angle) - dirY * Math.sin(angle);
        dirY = oldDirX * Math.sin(angle) + dirY * Math.cos(angle);
    }

    private void updateDoors(double deltaTime) {
        float dt = (float) deltaTime;

        for (int y = 0; y < MAP_HEIGHT; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                Door door = doorsMap[y][x];
                if (door == null) continue;

                switch (door.state) {
                    case OPENING:
                        door.offset += door.openSpeed * dt;
                        if (door.offset >= 1.0f) {
                            door.offset = 1.0f;
                            door.state = DoorState.OPEN;
                            door.timer = door.holdTime;
                        }
                        break;

                    case OPEN:
                        door.timer -= dt;
                        if (door.timer <= 0) {
                            if (!isPlayerInsideTile(door.x, door.y)) {
                                door.state = DoorState.CLOSING;
                            } else {
                                door.timer = 1.0f;
                            }
                        }
                        break;

                    case CLOSING:
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

    private boolean isPlayerInsideTile(int tileX, int tileY) {
        double radius = 0.3;
        return (posX + radius > tileX && posX - radius < tileX + 1.0) &&
                (posY + radius > tileY && posY - radius < tileY + 1.0);
    }

    private boolean canMoveTo(double targetX, double targetY) {
        int tileX = (int) Math.floor(targetX);
        int tileY = (int) Math.floor(targetY);

        if (tileX < 0 || tileX >= MAP_WIDTH || tileY < 0 || tileY >= MAP_HEIGHT) return false;

        int tile = MAP[tileY][tileX];
        if (tile == 0) return true;

        if (tile == 5) {
            Door door = doorsMap[tileY][tileX];
            return door != null && door.offset >= 0.8f;
        }

        return false;
    }

    private void updateWeaponAnimation(float dt) {
        if (cooldownTimer > 0) {
            cooldownTimer -= dt;
        }

        if (isShooting) {
            shootAnimTime -= dt;

            float progress = Math.max(0.0f, Math.min(1.0f, 1.0f - (shootAnimTime / SHOOT_ANIM_DURATION)));
            float recoilFactor = (float) Math.sin(progress * Math.PI);

            recoilOffsetY = recoilFactor * 45.0f;
            recoilOffsetX = (float) ((random.nextFloat() - 0.5f) * recoilFactor * 10.0f);

            if (shootAnimTime <= 0) {
                isShooting = false;
                recoilOffsetY = 0;
                recoilOffsetX = 0;
            }
        }
    }

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

            if (dist > 10) {
                double forwardScale = -dy / joyRadius;
                double strafeScale = dx / joyRadius;

                double moveX = (dirX * forwardScale + planeX * strafeScale) * MOVE_SPEED * deltaTime;
                double moveY = (dirY * forwardScale + planeY * strafeScale) * MOVE_SPEED * deltaTime;

                double padding = 0.3;

                if (canMoveTo(posX + moveX + Math.signum(moveX) * padding, posY)) {
                    posX += moveX;
                }
                if (canMoveTo(posX, posY + moveY + Math.signum(moveY) * padding)) {
                    posY += moveY;
                }
            }
        }
    }

    @Override
    public void run() {
        while (running) {
            if (!surfaceHolder.getSurface().isValid()) continue;

            long now = System.nanoTime();
            double deltaTime = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            if (deltaTime > 0) {
                float currentFps = (float) (1.0 / deltaTime);
                fps = fps * 0.95f + currentFps * 0.05f;
            }

            if (now - lastPerfUpdate > 1_000_000_000L) {
                Runtime runtime = Runtime.getRuntime();
                ramUsageBytes = runtime.totalMemory() - runtime.freeMemory();
                cachedFpsText = String.format("FPS: %.1f", fps);
                cachedRamText = String.format("RAM: %.1f MB", ramUsageBytes / (1024f * 1024f));
                lastPerfUpdate = now;
            }

            updatePhysics(deltaTime);

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

    private void renderGame(Canvas canvas) {
        int screenW = canvas.getWidth();
        int screenH = canvas.getHeight();

        float screenAspect = (float) screenW / screenH;

        // Расчет плоскости камеры для сохранения пропорций 4:3 без черных полос по бокам
        double planeLength = 0.66 * (screenAspect / (4.0 / 3.0));
        planeX = -dirY * planeLength;
        planeY = dirX * planeLength;

        int targetH = 300;
        if (graphicsQuality == 0) targetH = 240;
        else if (graphicsQuality == 1) targetH = 300;
        else if (graphicsQuality == 2) targetH = 480;

        int targetW = Math.round(targetH * screenAspect);

        if (renderBuffer == null || renderBuffer.getWidth() != targetW || renderBuffer.getHeight() != targetH || pendingQualityChange) {
            renderBuffer = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            frameBuffer = new int[targetW * targetH];
            pendingQualityChange = false;
        }

        int ceilColor = Color.rgb(20, 20, 30);
        int floorColor = Color.rgb(40, 40, 40);
        int halfSize = (targetW * targetH) / 2;
        Arrays.fill(frameBuffer, 0, halfSize, ceilColor);
        Arrays.fill(frameBuffer, halfSize, targetW * targetH, floorColor);

        for (int x = 0; x < targetW; x++) {
            double cameraX = 2 * x / (double) targetW - 1;
            double rayDirX = dirX + planeX * cameraX;
            double rayDirY = dirY + planeY * cameraX;

            int mapX = (int) posX;
            int mapY = (int) posY;

            double sideDistX, sideDistY;
            double deltaDistX = (rayDirX == 0) ? Double.MAX_VALUE : Math.abs(1 / rayDirX);
            double deltaDistY = (rayDirY == 0) ? Double.MAX_VALUE : Math.abs(1 / rayDirY);
            double perpWallDist = 0.0;

            int stepX, stepY;
            int hit = 0;
            int side = 0;
            double wallX = 0.0;

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
                    hit = -1;
                    break;
                }

                int tile = MAP[mapY][mapX];

                if (tile > 0) {
                    hit = tile;

                    if (side == 0) perpWallDist = (sideDistX - deltaDistX);
                    else          perpWallDist = (sideDistY - deltaDistY);

                    if (side == 0) wallX = posY + perpWallDist * rayDirY;
                    else          wallX = posX + perpWallDist * rayDirX;
                    wallX -= Math.floor(wallX);

                    if (tile == 5) {
                        Door door = doorsMap[mapY][mapX];
                        float doorOffset = (door != null) ? door.offset : 0.0f;

                        if (wallX < doorOffset) {
                            hit = 0;
                            wallX = 0.0;
                        } else {
                            wallX -= doorOffset;
                        }
                    }
                }
            }

            if (hit <= 0) continue;

            if (perpWallDist <= 0) perpWallDist = 0.01;

            int lineHeight = (int) (targetH / perpWallDist);
            int drawStart = -lineHeight / 2 + targetH / 2;
            int drawEnd = lineHeight / 2 + targetH / 2;

            int actualDrawStart = Math.max(0, drawStart);
            int actualDrawEnd = Math.min(targetH - 1, drawEnd);

            int texX = (int) (wallX * (double) TEX_SIZE);

            if (side == 0 && rayDirX > 0) texX = TEX_SIZE - texX - 1;
            if (side == 1 && rayDirY < 0) texX = TEX_SIZE - texX - 1;

            texX &= (TEX_SIZE - 1);

            double stepYTex = 1.0 * TEX_SIZE / lineHeight;
            double texPos = (actualDrawStart - targetH / 2.0 + lineHeight / 2.0) * stepYTex;

            int textureIndex = Math.min(hit, 5);

            double brightness = 1.0 - (perpWallDist / MAX_VISIBLE_DIST);
            if (brightness < 0.0) brightness = 0.0;
            if (brightness > 1.0) brightness = 1.0;

            if (side == 1) {
                brightness *= 0.75;
            }

            int shadeScale = (int) (brightness * 256);

            for (int y = actualDrawStart; y <= actualDrawEnd; y++) {
                int texY = (int) texPos & (TEX_SIZE - 1);
                texPos += stepYTex;

                int rawColor = texPixels[textureIndex][TEX_SIZE * texY + texX];

                int r = (((rawColor >> 16) & 0xFF) * shadeScale) >> 8;
                int g = (((rawColor >> 8) & 0xFF) * shadeScale) >> 8;
                int b = ((rawColor & 0xFF) * shadeScale) >> 8;

                frameBuffer[y * targetW + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        renderBuffer.setPixels(frameBuffer, 0, targetW, 0, 0, targetW, targetH);

        // Рендерим каповое изображение на весь экран без черных рамок
        renderDstRect.set(0, 0, screenW, screenH);
        canvas.drawBitmap(renderBuffer, null, renderDstRect, null);

        // Позиция джойстика
        if (joyCenterY == 0) {
            joyCenterX = screenW * 0.15f;
            joyCenterY = screenH * 0.72f;
            joyStickX = joyCenterX;
            joyStickY = joyCenterY;
        }

        // Отрисовка элементов интерфейса без фоновых черных квадратов
        canvas.drawCircle(joyCenterX, joyCenterY, joyRadius, joyBgPaint);
        canvas.drawCircle(joyStickX, joyStickY, joyStickRadius, joyStickPaint);

        drawWeapon(canvas, screenW, screenH);
        drawHUD(canvas, screenH);

        if (showMinimap) drawMiniMap(canvas, screenW);
        if (showPerformance) drawPerformanceOverlay(canvas);
    }

    private void drawWeapon(Canvas canvas, int screenW, int screenH) {
        if (weapons.isEmpty()) return;
        Weapon w = weapons.get(currentWeaponIndex);

        Bitmap gunSprite = (isShooting && shootAnimTime > 0) ? w.fireSprite : w.idleSprite;
        if (gunSprite == null) return;

        int gunWidth = (int) (screenH * 0.55f);
        int gunHeight = (int) (gunWidth * ((float) gunSprite.getHeight() / gunSprite.getWidth()));

        float left = (screenW - gunWidth) / 2.0f + recoilOffsetX;
        float top = screenH - gunHeight + recoilOffsetY;

        weaponSrcRect.set(0, 0, gunSprite.getWidth(), gunSprite.getHeight());
        weaponDstRect.set(left, top, left + gunWidth, top + gunHeight);

        weaponPaint.setFilterBitmap(false);
        canvas.drawBitmap(gunSprite, weaponSrcRect, weaponDstRect, weaponPaint);
    }

    private void drawHUD(Canvas canvas, int screenH) {
        if (weapons.isEmpty()) return;
        Weapon w = weapons.get(currentWeaponIndex);

        hudPaint.setColor(Color.YELLOW);
        hudPaint.setTextSize(36);

        canvas.drawText("GUN: " + w.name, 30, screenH - 50, hudPaint);
        canvas.drawText("AMMO: " + w.ammo + "/" + w.maxAmmo, 30, screenH - 15, hudPaint);
    }

    private void drawMiniMap(Canvas canvas, int screenW) {
        float size = 8f;
        float mapSize = MAP_WIDTH * size;
        float padding = 20f;
        float startX = screenW - mapSize - padding;
        float startY = padding;

        for (int y = 0; y < MAP_HEIGHT; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                int wallType = MAP[y][x];
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

        miniMapPaint.setColor(Color.YELLOW);
        float playerX = startX + (float) posX * size;
        float playerY = startY + (float) posY * size;
        canvas.drawCircle(playerX, playerY, 4f, miniMapPaint);

        miniMapPaint.setColor(Color.RED);
        miniMapPaint.setStrokeWidth(2f);
        canvas.drawLine(playerX, playerY, playerX + (float) dirX * 12f, playerY + (float) dirY * 12f, miniMapPaint);
    }

    private void drawPerformanceOverlay(Canvas canvas) {
        hudPaint.setColor(Color.GREEN);
        hudPaint.setTextSize(32);
        canvas.drawText(cachedFpsText, 30, 45, hudPaint);
        canvas.drawText(cachedRamText, 30, 85, hudPaint);
    }
}