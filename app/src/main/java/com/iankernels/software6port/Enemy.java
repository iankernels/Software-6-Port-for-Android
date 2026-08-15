package com.iankernels.software6port;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

public class Enemy {
    public double x, y;
    public double rotationAngle = 0.0;
    public int health = 50;
    public boolean isDead = false;
    public boolean isAlerted = false;

    public double speed = 1.5;
    public double attackRange = 10.0;
    public float attackCooldown = 0.0f;
    public float fireRate = 1.0f;
    public int damage = 10;
    public double radius = 0.25;

    private List<Node> currentPath = new ArrayList<>();
    private float pathRecalcTimer = 0.0f;
    private static final float PATH_RECALC_INTERVAL = 0.5f;

    public enum State { IDLE, CHASE, ATTACK, PAIN, DYING, DEAD }
    public State currentState = State.IDLE;

    private Bitmap[][] spriteGrid;
    private float animTimer = 0.0f;
    private int walkAnimFrame = 0;
    private int attackAnimFrame = 0;
    private int deathAnimFrame = 0;

    public Enemy(double x, double y, Bitmap spriteSheet) {
        this.x = x;
        this.y = y;
        sliceSpriteSheet(spriteSheet);
    }

    private void sliceSpriteSheet(Bitmap sheet) {
        if (sheet == null) return;

        int cols = 8;
        int rows = 11;
        int frameW = sheet.getWidth() / cols;
        int frameH = sheet.getHeight() / rows;

        spriteGrid = new Bitmap[rows][cols];

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Bitmap rawFrame = Bitmap.createBitmap(sheet, c * frameW, r * frameH, frameW, frameH);
                Bitmap transparentFrame = cleanCyanBackground(rawFrame);
                // Ensure uniform grid frame dimensions to prevent scaling jumps
                spriteGrid[r][c] = Bitmap.createScaledBitmap(transparentFrame, frameW, frameH, false);
            }
        }
    }

    private Bitmap cleanCyanBackground(Bitmap src) {
        Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, true);
        int width = copy.getWidth();
        int height = copy.getHeight();
        int[] pixels = new int[width * height];
        copy.getPixels(pixels, 0, width, 0, 0, width, height);

        float[] hsv = new float[3];
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            Color.colorToHSV(color, hsv);

            if (hsv[0] >= 160 && hsv[0] <= 210 && hsv[1] > 0.3f && hsv[2] > 0.3f) {
                pixels[i] = Color.TRANSPARENT;
            }
        }
        copy.setPixels(pixels, 0, width, 0, 0, width, height);
        return copy;
    }

    public void update(double deltaTime, double playerX, double playerY, int[][] map, List<Enemy> allEnemies, GameView gameView) {
        animTimer += (float) deltaTime;
        pathRecalcTimer += (float) deltaTime;

        if (currentState == State.DEAD) return;

        if (currentState == State.DYING) {
            if (animTimer >= 0.12f) {
                animTimer = 0.0f;
                deathAnimFrame++;
                if (deathAnimFrame >= 6) {
                    deathAnimFrame = 5;
                    currentState = State.DEAD;
                    isDead = true;
                }
            }
            return;
        }

        if (attackCooldown > 0) {
            attackCooldown -= (float) deltaTime;
        }

        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (!isAlerted && distance < attackRange) {
            if (hasLineOfSight(playerX, playerY, map)) {
                isAlerted = true;
            }
        }

        if (isAlerted) {
            rotationAngle = Math.atan2(dy, dx);

            if (distance > 1.2) {
                currentState = State.CHASE;

                if (pathRecalcTimer >= PATH_RECALC_INTERVAL || currentPath.isEmpty()) {
                    pathRecalcTimer = 0.0f;
                    currentPath = findPath((int) x, (int) y, (int) playerX, (int) playerY, map, gameView);
                }

                if (currentPath != null && !currentPath.isEmpty()) {
                    Node nextTile = currentPath.get(0);
                    double targetX = nextTile.x + 0.5;
                    double targetY = nextTile.y + 0.5;

                    double stepDx = targetX - x;
                    double stepDy = targetY - y;
                    double stepDist = Math.sqrt(stepDx * stepDx + stepDy * stepDy);

                    if (stepDist < 0.2) {
                        currentPath.remove(0);
                    } else {
                        double moveX = (stepDx / stepDist) * speed * deltaTime;
                        double moveY = (stepDy / stepDist) * speed * deltaTime;

                        if (canMoveTo(x + moveX, y, map, gameView)) x += moveX;
                        if (canMoveTo(x, y + moveY, map, gameView)) y += moveY;
                    }
                }

                if (animTimer >= 0.18f) {
                    animTimer = 0.0f;
                    walkAnimFrame = (walkAnimFrame + 1) % 4;
                }
            } else {
                currentState = State.ATTACK;

                if (animTimer >= 0.15f) {
                    animTimer = 0.0f;
                    attackAnimFrame = (attackAnimFrame + 1) % 2;
                }

                if (attackCooldown <= 0) {
                    attackCooldown = fireRate;
                    gameView.damagePlayer(damage);
                }
            }
        } else {
            currentState = State.IDLE;
            walkAnimFrame = 0;
            attackAnimFrame = 0;
        }

        resolveEnemyCollisions(allEnemies, map, gameView);
    }

    private boolean hasLineOfSight(double playerX, double playerY, int[][] map) {
        double dx = playerX - x;
        double dy = playerY - y;
        double distance = Math.sqrt(dx * dx + dy * dy);

        double stepX = dx / distance / 5.0;
        double stepY = dy / distance / 5.0;

        double currX = x;
        double currY = y;

        for (int i = 0; i < (int)(distance * 5); i++) {
            currX += stepX;
            currY += stepY;

            int tileX = (int) Math.floor(currX);
            int tileY = (int) Math.floor(currY);

            if (tileY >= 0 && tileY < map.length && tileX >= 0 && tileX < map[0].length) {
                if (map[tileY][tileX] > 0 && map[tileY][tileX] != 5) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean canMoveTo(double targetX, double targetY, int[][] map, GameView gameView) {
        int mapWidth = map[0].length;
        int mapHeight = map.length;

        for (int angle = 0; angle < 360; angle += 45) {
            double rad = Math.toRadians(angle);
            double checkX = targetX + radius * Math.cos(rad);
            double checkY = targetY + radius * Math.sin(rad);

            int tileX = (int) Math.floor(checkX);
            int tileY = (int) Math.floor(checkY);

            if (tileX < 0 || tileX >= mapWidth || tileY < 0 || tileY >= mapHeight) return false;

            int tile = map[tileY][tileX];
            if (tile > 0) {
                if (tile == 5) {
                    GameView.Door door = gameView.getDoor(tileX, tileY);
                    if (door == null || door.offset < 0.8f) return false;
                } else {
                    return false;
                }
            }
        }
        return true;
    }

    private void resolveEnemyCollisions(List<Enemy> allEnemies, int[][] map, GameView gameView) {
        for (Enemy other : allEnemies) {
            if (other == this || other.isDead) continue;

            double edx = other.x - this.x;
            double edy = other.y - this.y;
            double dist = Math.sqrt(edx * edx + edy * edy);
            double minDist = this.radius + other.radius;

            if (dist < minDist && dist > 0.0001) {
                double overlap = minDist - dist;
                double pushX = (edx / dist) * (overlap / 2.0);
                double pushY = (edy / dist) * (overlap / 2.0);

                if (canMoveTo(this.x - pushX, this.y, map, gameView)) this.x -= pushX;
                if (canMoveTo(this.x, this.y - pushY, map, gameView)) this.y -= pushY;

                if (canMoveTo(other.x + pushX, other.y, map, gameView)) other.x += pushX;
                if (canMoveTo(other.x, other.y + pushY, map, gameView)) other.y += pushY;
            }
        }
    }

    private static class Node implements Comparable<Node> {
        int x, y;
        double gCost, hCost, fCost;
        Node parent;

        Node(int x, int y) {
            this.x = x;
            this.y = y;
        }

        void calculateFCost() {
            this.fCost = gCost + hCost;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fCost, o.fCost);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Node)) return false;
            Node node = (Node) o;
            return x == node.x && y == node.y;
        }
    }

    private List<Node> findPath(int startX, int startY, int targetX, int targetY, int[][] map, GameView gameView) {
        int mapWidth = map[0].length;
        int mapHeight = map.length;

        if (targetX < 0 || targetX >= mapWidth || targetY < 0 || targetY >= mapHeight) return null;

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        boolean[][] closedSet = new boolean[mapHeight][mapWidth];

        Node startNode = new Node(startX, startY);
        startNode.gCost = 0;
        startNode.hCost = Math.hypot(targetX - startX, targetY - startY);
        startNode.calculateFCost();

        openSet.add(startNode);

        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();
            if (current == null) break;

            if (current.x == targetX && current.y == targetY) {
                List<Node> path = new ArrayList<>();
                Node curr = current;
                while (curr != null) {
                    path.add(curr);
                    curr = curr.parent;
                }
                Collections.reverse(path);
                if (!path.isEmpty()) path.remove(0);
                return path;
            }

            closedSet[current.y][current.x] = true;

            for (int[] dir : dirs) {
                int neighborX = current.x + dir[0];
                int neighborY = current.y + dir[1];

                if (neighborX < 0 || neighborX >= mapWidth || neighborY < 0 || neighborY >= mapHeight) continue;
                if (closedSet[neighborY][neighborX]) continue;

                int tile = map[neighborY][neighborX];
                if (tile > 0) {
                    if (tile == 5) {
                        GameView.Door door = gameView.getDoor(neighborX, neighborY);
                        if (door == null || door.offset < 0.8f) continue;
                    } else {
                        continue;
                    }
                }

                double newGCost = current.gCost + 1.0;
                Node neighbor = new Node(neighborX, neighborY);

                Node existingInOpen = null;
                for (Node n : openSet) {
                    if (n.equals(neighbor)) {
                        existingInOpen = n;
                        break;
                    }
                }

                if (existingInOpen == null || newGCost < existingInOpen.gCost) {
                    neighbor.gCost = newGCost;
                    neighbor.hCost = Math.hypot(targetX - neighborX, targetY - neighborY);
                    neighbor.calculateFCost();
                    neighbor.parent = current;

                    if (existingInOpen != null) openSet.remove(existingInOpen);
                    openSet.add(neighbor);
                }
            }
        }
        return null;
    }

    public void takeDamage(int amount) {
        if (currentState == State.DYING || currentState == State.DEAD) return;

        isAlerted = true;
        health -= amount;
        if (health <= 0) {
            health = 0;
            currentState = State.DYING;
            deathAnimFrame = 0;
            animTimer = 0.0f;
        } else {
            currentState = State.PAIN;
        }
    }

    private int getDirectionalIndex(double playerX, double playerY) {
        double angleToPlayer = Math.atan2(playerY - y, playerX - x);
        double relativeAngle = angleToPlayer - rotationAngle;

        while (relativeAngle < 0) relativeAngle += 2 * Math.PI;
        while (relativeAngle >= 2 * Math.PI) relativeAngle -= 2 * Math.PI;

        return (int) Math.round(relativeAngle / (Math.PI / 4.0)) % 8;
    }

    public Bitmap getCurrentFrame(double playerX, double playerY) {
        if (spriteGrid == null) return null;

        int dirIndex = getDirectionalIndex(playerX, playerY);

        switch (currentState) {
            case CHASE:
                return spriteGrid[walkAnimFrame][dirIndex];

            case ATTACK:
                int attackRow = (attackAnimFrame == 1) ? 5 : 6;
                return spriteGrid[attackRow][dirIndex];

            case PAIN:
                return spriteGrid[4][dirIndex];

            case DYING:
            case DEAD:
                return spriteGrid[7][Math.min(deathAnimFrame, 5)];

            case IDLE:
            default:
                return spriteGrid[6][dirIndex];
        }
    }
}