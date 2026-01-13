import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;


/**
 * CoinRushStage - Java Swing 2D Mini Game (single file)
 *
 * Features:
 *  - Difficulty select (E/N/H) on title
 *  - Items: SLOW (enemy speed down), SHIELD (damage ignore)
 *  - Difficulty affects item spawn rate & effect durations
 *  - Stage progression: reach score threshold -> stage up -> add enemies & slightly increase speed
 *
 * Controls:
 *  - Arrow keys: Move
 *  - E/N/H: Select difficulty (on title)
 *  - ENTER: Start (on title)
 *  - R: Restart
 *  - ESC: Exit (on game over)
 */
public class CoinRushStage extends JPanel implements ActionListener, KeyListener {

    // =========================
    // COMMON CONFIG
    // =========================
    private static final int WINDOW_W = 800;
    private static final int WINDOW_H = 600;
    private static final int FPS = 60;

    private static final int UI_BAR_H = 40;

    private static final int PLAYER_SIZE = 26;
    private static final int PLAYER_SPEED = 5;

    private static final int COIN_SIZE = 16;
    private static final int ENEMY_SIZE = 24;

    private static final int START_LIFE = 3;
    private static final int COIN_COUNT = 12;

    private static final int HIT_INVINCIBLE_MS = 900;
    private static final int SCORE_PER_COIN = 100;

    // Items
    private static final int ITEM_SIZE = 18;
    private static final int ITEM_MAX_ON_FIELD = 1;

    // SLOW: multiplier for enemy speed
    private static final double SLOW_MULTIPLIER = 0.45;

    // Stage progression
    private static final int STAGE_SCORE_STEP = 800;       // このスコアごとにステージUP
    private static final int MAX_STAGE = 30;               // 念のため
    private static final int ENEMY_ADD_PER_STAGE = 1;      // ステージUPで敵+1
    private static final int SPEED_ADD_EVERY_STAGE = 1;    // 速度補正の更新頻度（1=毎ステージ）
    private static final double SPEED_SCALE_PER_STAGE = 0.06; // 1ステージあたり速度+6%（体感調整用）

    // =========================
    // DIFFICULTY
    // =========================
    private enum Difficulty {
        EASY("EASY", 75, 3, 2, 3,
                2800, 5200, // item spawn min/max (ms)  <- 出やすい
                6000, 5500  // slow/shield duration (ms) <- 長い
        ),
        NORMAL("NORMAL", 60, 4, 2, 4,
                4000, 7500,
                5000, 4500
        ),
        HARD("HARD", 45, 6, 3, 5,
                5200, 9800, // item spawn min/max (ms)  <- 出にくい
                4200, 3500  // slow/shield duration (ms) <- 短い
        );

        final String label;
        final int timeLimitSec;
        final int startEnemyCount;
        final int enemySpeedMin;
        final int enemySpeedMax;

        final int itemSpawnMinMs;
        final int itemSpawnMaxMs;
        final int slowDurationMs;
        final int shieldDurationMs;

        Difficulty(String label, int timeLimitSec, int startEnemyCount,
                   int enemySpeedMin, int enemySpeedMax,
                   int itemSpawnMinMs, int itemSpawnMaxMs,
                   int slowDurationMs, int shieldDurationMs) {
            this.label = label;
            this.timeLimitSec = timeLimitSec;
            this.startEnemyCount = startEnemyCount;
            this.enemySpeedMin = enemySpeedMin;
            this.enemySpeedMax = enemySpeedMax;
            this.itemSpawnMinMs = itemSpawnMinMs;
            this.itemSpawnMaxMs = itemSpawnMaxMs;
            this.slowDurationMs = slowDurationMs;
            this.shieldDurationMs = shieldDurationMs;
        }
    }

    private Difficulty difficulty = Difficulty.NORMAL;

    // =========================
    // GAME STATE
    // =========================
    private enum State { TITLE, PLAYING, GAME_OVER }
    private State state = State.TITLE;

    private final Timer timer = new Timer(1000 / FPS, this);
    private final Random rnd = new Random();

    private int score = 0;
    private int life = START_LIFE;

    private int stage = 1;
    private int nextStageScore = STAGE_SCORE_STEP;

    private long startTimeMs = 0;
    private long lastHitMs = -999999;

    private long slowUntilMs = 0;
    private long shieldUntilMs = 0;

    private long nextItemSpawnMs = 0;

    private boolean up, down, left, right;

    // Entities
    private Rectangle player;
    private final List<Rectangle> coins = new ArrayList<>();
    private final List<Enemy> enemies = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();

    private static class Enemy {
        Rectangle rect;
        int vx, vy; // base velocity (will be scaled by stage & slow)
        Enemy(Rectangle r, int vx, int vy) { this.rect = r; this.vx = vx; this.vy = vy; }
    }

    private enum ItemType { SLOW, SHIELD }

    private static class Item {
        Rectangle rect;
        ItemType type;
        Item(Rectangle rect, ItemType type) { this.rect = rect; this.type = type; }
    }

    // =========================
    // SETUP
    // =========================
    public CoinRushStage() {
        setPreferredSize(new Dimension(WINDOW_W, WINDOW_H));
        setFocusable(true);
        addKeyListener(this);
        initToTitle();
        timer.start();
    }

    private void initToTitle() {
        score = 0;
        life = START_LIFE;
        stage = 1;
        nextStageScore = STAGE_SCORE_STEP;

        up = down = left = right = false;
        state = State.TITLE;

        slowUntilMs = 0;
        shieldUntilMs = 0;
        nextItemSpawnMs = 0;

        player = new Rectangle(WINDOW_W / 2 - PLAYER_SIZE / 2,
                WINDOW_H / 2 - PLAYER_SIZE / 2,
                PLAYER_SIZE, PLAYER_SIZE);

        coins.clear();
        enemies.clear();
        items.clear();

        for (int i = 0; i < COIN_COUNT; i++) {
            coins.add(spawnRect(COIN_SIZE, COIN_SIZE));
        }

        buildEnemies(difficulty.startEnemyCount);
    }

    private void startPlay() {
        state = State.PLAYING;
        startTimeMs = System.currentTimeMillis();
        lastHitMs = -999999;

        slowUntilMs = 0;
        shieldUntilMs = 0;

        items.clear();
        scheduleNextItemSpawn(System.currentTimeMillis());
    }

    private void buildEnemies(int count) {
        enemies.clear();
        for (int i = 0; i < count; i++) {
            enemies.add(spawnEnemy());
        }
    }

    private Enemy spawnEnemy() {
        Rectangle r = spawnRect(ENEMY_SIZE, ENEMY_SIZE);
        int vx = randSign() * randBetween(difficulty.enemySpeedMin, difficulty.enemySpeedMax);
        int vy = randSign() * randBetween(difficulty.enemySpeedMin, difficulty.enemySpeedMax);
        return new Enemy(r, vx, vy);
    }

    private Rectangle spawnRect(int w, int h) {
        int x = rnd.nextInt(Math.max(1, WINDOW_W - w - 40)) + 20;
        int y = rnd.nextInt(Math.max(1, WINDOW_H - h - 80)) + 60; // leave UI space
        return new Rectangle(x, y, w, h);
    }

    private int randBetween(int a, int b) {
        return a + rnd.nextInt(b - a + 1);
    }

    private int randSign() {
        return rnd.nextBoolean() ? 1 : -1;
    }

    private void scheduleNextItemSpawn(long now) {
        long delta = randBetween(difficulty.itemSpawnMinMs, difficulty.itemSpawnMaxMs);
        nextItemSpawnMs = now + delta;
    }

    // =========================
    // LOOP
    // =========================
    @Override
    public void actionPerformed(ActionEvent e) {
        long now = System.currentTimeMillis();

        if (state == State.PLAYING) {
            updatePlayer();
            updateEnemies(now);
            spawnItemsIfNeeded(now);
            checkCoinPickup();
            checkItemPickup(now);
            checkEnemyHit(now);
            checkStageUp(now);
            checkTimeOver(now);
        }

        repaint();
    }

    private void updatePlayer() {
        int dx = 0, dy = 0;
        if (left) dx -= PLAYER_SPEED;
        if (right) dx += PLAYER_SPEED;
        if (up) dy -= PLAYER_SPEED;
        if (down) dy += PLAYER_SPEED;

        player.x += dx;
        player.y += dy;

        player.x = clamp(player.x, 0, WINDOW_W - player.width);
        player.y = clamp(player.y, UI_BAR_H, WINDOW_H - player.height);
    }

    private void updateEnemies(long now) {
        boolean slow = now < slowUntilMs;
        double slowMul = slow ? SLOW_MULTIPLIER : 1.0;

        // Stage speed scaling (e.g., stage 1 = 1.0, stage 2 = 1.06, ...)
        double stageMul = 1.0 + SPEED_SCALE_PER_STAGE * Math.max(0, stage - 1);

        for (Enemy en : enemies) {
            int dx = (int) Math.round(en.vx * stageMul * slowMul);
            int dy = (int) Math.round(en.vy * stageMul * slowMul);

            // ensure movement when slowed/scaled
            if (dx == 0) dx = (en.vx > 0) ? 1 : -1;
            if (dy == 0) dy = (en.vy > 0) ? 1 : -1;

            en.rect.x += dx;
            en.rect.y += dy;

            // bounce
            if (en.rect.x < 0) { en.rect.x = 0; en.vx *= -1; }
            if (en.rect.x > WINDOW_W - en.rect.width) { en.rect.x = WINDOW_W - en.rect.width; en.vx *= -1; }
            if (en.rect.y < UI_BAR_H) { en.rect.y = UI_BAR_H; en.vy *= -1; }
            if (en.rect.y > WINDOW_H - en.rect.height) { en.rect.y = WINDOW_H - en.rect.height; en.vy *= -1; }
        }
    }

    private void spawnItemsIfNeeded(long now) {
        if (items.size() >= ITEM_MAX_ON_FIELD) return;
        if (now < nextItemSpawnMs) return;

        ItemType type = rnd.nextBoolean() ? ItemType.SLOW : ItemType.SHIELD;
        items.add(new Item(spawnRect(ITEM_SIZE, ITEM_SIZE), type));
        scheduleNextItemSpawn(now);
    }

    private void checkCoinPickup() {
        for (int i = coins.size() - 1; i >= 0; i--) {
            Rectangle c = coins.get(i);
            if (player.intersects(c)) {
                score += SCORE_PER_COIN;
                coins.set(i, spawnRect(COIN_SIZE, COIN_SIZE));
            }
        }
    }

    private void checkItemPickup(long now) {
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (player.intersects(it.rect)) {
                if (it.type == ItemType.SLOW) {
                    slowUntilMs = Math.max(slowUntilMs, now + difficulty.slowDurationMs);
                } else {
                    shieldUntilMs = Math.max(shieldUntilMs, now + difficulty.shieldDurationMs);
                }
                items.remove(i);
            }
        }
    }

    private void checkEnemyHit(long now) {
        boolean postHitInv = (now - lastHitMs) < HIT_INVINCIBLE_MS;
        boolean shield = now < shieldUntilMs;
        if (postHitInv || shield) return;

        for (Enemy en : enemies) {
            if (player.intersects(en.rect)) {
                life--;
                lastHitMs = now;

                player.x = clamp(player.x + randSign() * 20, 0, WINDOW_W - player.width);
                player.y = clamp(player.y + randSign() * 20, UI_BAR_H, WINDOW_H - player.height);

                if (life <= 0) state = State.GAME_OVER;
                break;
            }
        }
    }

    private void checkStageUp(long now) {
        if (stage >= MAX_STAGE) return;

        while (score >= nextStageScore && stage < MAX_STAGE) {
            stage++;
            nextStageScore += STAGE_SCORE_STEP;

            // add enemies each stage
            for (int i = 0; i < ENEMY_ADD_PER_STAGE; i++) enemies.add(spawnEnemy());

            // optional: small feedback by spawning an item sometimes (planner-ish reward)
            // Here: every 3 stages spawn one guaranteed item (if none on field)
            if (stage % 3 == 0 && items.size() < ITEM_MAX_ON_FIELD) {
                ItemType t = rnd.nextBoolean() ? ItemType.SLOW : ItemType.SHIELD;
                items.add(new Item(spawnRect(ITEM_SIZE, ITEM_SIZE), t));
                scheduleNextItemSpawn(now);
            }

            // speed is handled by stage multiplier (no need to mutate vx/vy)
            // SPEED_ADD_EVERY_STAGE kept for future extensions; currently stage multiplier is continuous.
        }
    }

    private void checkTimeOver(long now) {
        if (getRemainTimeSec(now) <= 0) state = State.GAME_OVER;
    }

    private int getRemainTimeSec(long now) {
        long elapsedMs = now - startTimeMs;
        int elapsedSec = (int) (elapsedMs / 1000);
        return Math.max(0, difficulty.timeLimitSec - elapsedSec);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // =========================
    // RENDER
    // =========================
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // background
        g.setColor(new Color(18, 18, 22));
        g.fillRect(0, 0, WINDOW_W, WINDOW_H);

        // UI
        g.setColor(new Color(30, 30, 38));
        g.fillRect(0, 0, WINDOW_W, UI_BAR_H);

        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("Score: " + score, 12, 25);
        g.drawString("Life: " + life, 140, 25);
        g.drawString("Difficulty: " + difficulty.label, 240, 25);

        long now = System.currentTimeMillis();

        if (state == State.PLAYING) {
            g.drawString("Time: " + getRemainTimeSec(now), 430, 25);
            g.drawString("Stage: " + stage, 540, 25);
            g.drawString("Next: " + Math.max(0, nextStageScore - score), 630, 25);

            int slowLeft = Math.max(0, (int) ((slowUntilMs - now) / 1000));
            int shieldLeft = Math.max(0, (int) ((shieldUntilMs - now) / 1000));

            if (slowLeft > 0) g.drawString("SLOW " + slowLeft + "s", 12, UI_BAR_H + 20);
            if (shieldLeft > 0) g.drawString("SHIELD " + shieldLeft + "s", 120, UI_BAR_H + 20);

        } else {
            g.drawString("Time: " + difficulty.timeLimitSec, 430, 25);
            g.drawString("Stage: " + stage, 540, 25);
        }

        // coins
        g.setColor(new Color(255, 215, 0));
        for (Rectangle c : coins) g.fillOval(c.x, c.y, c.width, c.height);

        // items
        for (Item it : items) {
            if (it.type == ItemType.SLOW) {
                g.setColor(new Color(120, 220, 255));
                g.fillOval(it.rect.x, it.rect.y, it.rect.width, it.rect.height);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.BOLD, 12));
                g.drawString("S", it.rect.x + 6, it.rect.y + 13);
            } else {
                g.setColor(new Color(160, 255, 160));
                g.fillOval(it.rect.x, it.rect.y, it.rect.width, it.rect.height);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.BOLD, 12));
                g.drawString("P", it.rect.x + 6, it.rect.y + 13);
            }
        }

        // enemies (purple when slow)
        boolean slow = now < slowUntilMs;
        g.setColor(slow ? new Color(200, 120, 255) : new Color(240, 80, 90));
        for (Enemy en : enemies) g.fillRect(en.rect.x, en.rect.y, en.rect.width, en.rect.height);

        // player
        boolean postHitInv = (now - lastHitMs) < HIT_INVINCIBLE_MS;
        boolean shield = now < shieldUntilMs;
        boolean visible = !postHitInv || ((now / 100) % 2 == 0);

        if (visible) {
            g.setColor(new Color(80, 180, 255));
            g.fillRect(player.x, player.y, player.width, player.height);
            if (shield) {
                g.setColor(Color.WHITE);
                g.drawRect(player.x - 2, player.y - 2, player.width + 4, player.height + 4);
            }
        }

        // overlays
        g.setColor(Color.WHITE);
        if (state == State.TITLE) {
            drawCentered(g, "COIN RUSH STAGE", WINDOW_H / 2 - 70, 44);
            drawCentered(g, "難易度選択: [E] EASY  [N] NORMAL  [H] HARD", WINDOW_H / 2 - 20, 18);
            drawCentered(g, "ENTERで開始 / 矢印キーで移動 / Rでリセット", WINDOW_H / 2 + 10, 18);
            drawCentered(g, "アイテム: SLOW(敵減速) / SHIELD(ダメージ無効)", WINDOW_H / 2 + 38, 18);
            drawCentered(g, "ステージ: スコアが " + STAGE_SCORE_STEP + " ごとに敵が増えて難しくなる", WINDOW_H / 2 + 66, 18);

            drawCentered(g, "現在: " + difficulty.label +
                    "（アイテム出現: " + itemSpawnText() +
                    " / 効果時間: " + effectText() + "）", WINDOW_H / 2 + 100, 16);

        } else if (state == State.GAME_OVER) {
            drawCentered(g, "GAME OVER", WINDOW_H / 2 - 30, 44);
            drawCentered(g, "Score: " + score + " / Stage: " + stage, WINDOW_H / 2 + 10, 22);
            drawCentered(g, "Rでリトライ / ESCで終了", WINDOW_H / 2 + 45, 18);
        }
    }

    private String itemSpawnText() {
        // shorter interval = more frequent
        int min = difficulty.itemSpawnMinMs / 1000;
        int max = difficulty.itemSpawnMaxMs / 1000;
        return min + "-" + max + "s";
    }

    private String effectText() {
        return "SLOW " + (difficulty.slowDurationMs / 1000) + "s, SHIELD " + (difficulty.shieldDurationMs / 1000) + "s";
    }

    private void drawCentered(Graphics g, String text, int y, int size) {
        Font f = new Font("SansSerif", Font.BOLD, size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int x = (WINDOW_W - fm.stringWidth(text)) / 2;
        g.drawString(text, x, y);
    }

    // =========================
    // INPUT
    // =========================
    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();

        if (state == State.TITLE) {
            if (k == KeyEvent.VK_E) { difficulty = Difficulty.EASY; initToTitle(); }
            if (k == KeyEvent.VK_N) { difficulty = Difficulty.NORMAL; initToTitle(); }
            if (k == KeyEvent.VK_H) { difficulty = Difficulty.HARD; initToTitle(); }

            if (k == KeyEvent.VK_ENTER) {
                // reset but keep difficulty
                score = 0;
                life = START_LIFE;
                stage = 1;
                nextStageScore = STAGE_SCORE_STEP;

                coins.clear();
                for (int i = 0; i < COIN_COUNT; i++) coins.add(spawnRect(COIN_SIZE, COIN_SIZE));

                buildEnemies(difficulty.startEnemyCount);

                player.x = WINDOW_W / 2 - PLAYER_SIZE / 2;
                player.y = WINDOW_H / 2 - PLAYER_SIZE / 2;

                startPlay();
            }

            if (k == KeyEvent.VK_R) initToTitle();
        }
        else if (state == State.GAME_OVER) {
            if (k == KeyEvent.VK_R) {
                // retry with same difficulty
                score = 0;
                life = START_LIFE;
                stage = 1;
                nextStageScore = STAGE_SCORE_STEP;

                coins.clear();
                for (int i = 0; i < COIN_COUNT; i++) coins.add(spawnRect(COIN_SIZE, COIN_SIZE));

                buildEnemies(difficulty.startEnemyCount);

                player.x = WINDOW_W / 2 - PLAYER_SIZE / 2;
                player.y = WINDOW_H / 2 - PLAYER_SIZE / 2;

                startPlay();
            }
            if (k == KeyEvent.VK_ESCAPE) System.exit(0);
        }
        else if (state == State.PLAYING) {
            if (k == KeyEvent.VK_LEFT) left = true;
            if (k == KeyEvent.VK_RIGHT) right = true;
            if (k == KeyEvent.VK_UP) up = true;
            if (k == KeyEvent.VK_DOWN) down = true;

            if (k == KeyEvent.VK_R) {
                // restart instantly with same difficulty
                score = 0;
                life = START_LIFE;
                stage = 1;
                nextStageScore = STAGE_SCORE_STEP;

                coins.clear();
                for (int i = 0; i < COIN_COUNT; i++) coins.add(spawnRect(COIN_SIZE, COIN_SIZE));

                buildEnemies(difficulty.startEnemyCount);

                player.x = WINDOW_W / 2 - PLAYER_SIZE / 2;
                player.y = WINDOW_H / 2 - PLAYER_SIZE / 2;

                startPlay();
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_LEFT) left = false;
        if (k == KeyEvent.VK_RIGHT) right = false;
        if (k == KeyEvent.VK_UP) up = false;
        if (k == KeyEvent.VK_DOWN) down = false;
    }

    @Override
    public void keyTyped(KeyEvent e) {}

    // =========================
    // BOOT
    // =========================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("CoinRushStage (Java 2D)");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            f.setContentPane(new CoinRushStage());
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
