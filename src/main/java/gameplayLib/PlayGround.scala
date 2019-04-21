package gameplayLib


import gameplayLib.CounterType.CounterType
import gameplayLib.Phrase.Phrase

import scala.collection.immutable
import scala.util.Random


case class SpawnedCard(who: String, cards: Seq[Card])


case class needCounter(shape: Option[Shape],

                       counterHistorySpawn: Seq[SpawnedCard])

//needCounterShape：需要对抗的牌型，如果有出牌权，那么就需要对抗此Shape
//counterHistorySpawn：对抗的历史出牌，如果有需要对抗时对抗失败，则消灭这个needCounter，触发一些效果，对抗成功则把needCounter加入自己出的牌shape的点数，更新shape再转移给其他玩家

case class OnePlayerStatus(var bombNeedNum: Int = Config.startBombNeedNum,
                           var handCards: Seq[Card] = Nil: Seq[Card], //handCards ：手上的牌
                           var HP: Int = Config.initHitPoint, var attack: Int = 0, var defence: Int = 0, //最初的属性
                           var buffs: Seq[Buff] = Nil: Seq[Buff],
                           var characters: Seq[Character] = Nil: Seq[Character],
                           var needCounter: needCounter = new needCounter(None, Nil)) //需要对抗的牌型和历史记录,如果为空则不需要按照对抗出牌
{
  def addCharacters(cSeq: Seq[Character]): OnePlayerStatus = {
    this.characters = cSeq ++ this.characters
    val atk = this.characters.map(x => x.attack).sum
    val defence = this.characters.map(x => x.defence).sum
    this.attack = atk
    this.defence = defence
    this
  }

  def drawAPlayerCards(Cards: Seq[Card]): OnePlayerStatus = {
    this.handCards = Card.sortCard(Cards ++ this.handCards)
    this
  }

  def spendCards(Idx: Array[Int]): OnePlayerStatus = {
    val hd = this.handCards
    this.handCards = ((1 to hd.count(_ => true)).toSet -- Idx.toSet).map(i => hd(i - 1)).toSeq
    this
  }

  def clearNeedCounter(): OnePlayerStatus = {
    this.needCounter = new needCounter(None, Nil)
    this
  }

}

object Phrase extends Enumeration { //阶段分类
type Phrase = Value
  val Prepare: Phrase = Value
  val ChooseCharacters: Phrase = Value
  val DrawCards: Phrase = Value
  val Check: Phrase = Value
  val FormCards: Phrase = Value
  val Spawn: Phrase = Value
  val Attack: Phrase = Value
  val Damage: Phrase = Value
}

object CounterType extends Enumeration { //阶段分类
type CounterType = Value
  val Normal: CounterType = Value
  val Bomb: CounterType = Value

}

object Position extends Enumeration { //位置分类
type Position = Value
  val DrawDeck: Position = Value
  val DrpDeck: Position = Value
  val MySpawnCards: Position = Value
  val MyHandCards: Position = Value
  val OtherSpawnCards: Position = Value
  val OtherHandCards: Position = Value
}


case class GamePlayGround(var drawDeck: Seq[Card] = Nil: Seq[Card], //抽牌堆，公共一个 ，如果没有牌，则
                          var dropDeck: Seq[Card] = Nil: Seq[Card], //弃牌堆，公共一个
                          var destroyedDeck: Seq[Card] = Nil: Seq[Card], //毁掉的牌，不再循环
                          var playersStatus: Map[String, OnePlayerStatus] = Map(), // 玩家id 座位号 玩家牌状态，可以用于多于两个人的情况
                          var characterPool: Seq[Character] = Nil: Seq[Character],
                          var chosenPool: Seq[Character] = Nil: Seq[Character],
                          var choosePoolsForCheck: Map[String, Seq[Int]] = Map(), //供个玩家选择的英雄池
                          var totalTurn: Int = 1, //总计轮数
                          var nowTurnSeat: Int = 1, //轮到座位几出牌
                          var nowPhrase: Phrase = Phrase.Prepare,
                          var turn: Int = 1, //回合，一次轮换出牌对象为一回合
                          var round: Int = 1, //轮，一方打完牌再弃牌重新抽牌为1轮
                          var nowTurnDamageMap: Map[String, Seq[Int]] = Map(), //在一轮伤害流程前，列出所有人会受到的伤害值序列，在伤害流程计算防御扣除血量
                          var maxPlayerNum: Int = 0, // 最大的座位数
                          var seat2Player: Map[Int, String] = Map(), //座位的玩家 情况
                          var nowPlayerNum: Int = 0, //当前玩家数量
                          var Outers: Seq[String] = Nil: Seq[String], //被淘汰的选手顺序约后面越先被淘汰
                          var summonPoint: (Int, Int) = (0, 0) //召唤值，达到一定值时，双方召唤一个新角色
                         ) { //每个房间需new1个新的playground

  def initPlayGround(players: Array[String], charactersIds: Array[Int]): Unit = {
    val playerNum = players.count(_ => true)
    this.maxPlayerNum = playerNum
    this.nowPlayerNum = playerNum
    val pairs = (1 to playerNum) zip players
    this.seat2Player = pairs.toMap
    val pool: Seq[Character] = charactersIds.map(id => Character.initCharacter(id))
    this.drawDeck = Config.normalCards ++ Config.normalCards ++ this.drawDeck
    this.characterPool = pool
    for (player <- players) {
      this.playersStatus += player -> OnePlayerStatus()
    }
  } //初始化玩家状态的过程

  def canSummonNew(): Boolean = {
    val summonLevel = this.summonPoint._1
    val nowSummonPoint = this.summonPoint._2
    val pointNeed = Config.summonPoints(summonLevel)
    nowSummonPoint >= pointNeed
  }

  def genPlayerChoosesFromCharacterPool(chooseNum: Int): Map[String, Seq[Int]] = { //给玩家生成各自的角色选择池，供玩家选择
    this.nowPhrase = Phrase.ChooseCharacters
    val newSummonLevel = this.summonPoint._1 + 1
    this.summonPoint = (newSummonLevel, 0)
    val oPool = Random.shuffle(this.characterPool.map(_.id))
    val characterNum = oPool.count(_ => true)
    var rMap: Map[String, Seq[Int]] = Map()
    val realChooseNum: Int = chooseNum match {
      case cNum if cNum * this.nowPlayerNum <= characterNum => cNum
      case _ => characterNum / this.maxPlayerNum
    }
    val (idSeq, _) = sliceToPieces(this.nowPlayerNum, realChooseNum, oPool)
    val players = this.playersStatus.keys
    val tuples = players zip idSeq
    for ((x, y) <- tuples) {
      rMap += x -> y
    }
    this.choosePoolsForCheck = rMap
    rMap
  }

  def getCIdFromChooseMap(chooses: Map[String, Int]): Map[String, Int] = //从选择号选出一个角色
    chooses.map(aChoice => {
      val id = aChoice._1
      val idx = aChoice._2
      val choosePools: Seq[Int] = this.choosePoolsForCheck(id)
      id -> choosePools((idx - 1) % choosePools.count(_ => true))
    })


  def updateCharacterPoolAfterPlayerChooseAndDrawDeck(chooses: Map[String, Int]): Boolean = { //把选择的角色分配给在场玩家
    val chooseCIds = getCIdFromChooseMap(chooses)
    val cidS = chooseCIds.values
    val cidSet = cidS.toSet
    if (cidS.count(_ => true) == cidSet.count(_ => true)) {
      val oPool = this.characterPool
      this.characterPool = oPool.filter(x => !cidSet.contains(x.id))
      val cPool = oPool.filter(x => cidSet.contains(x.id))
      this.chosenPool = cPool
      chooseCIds.foreach(
        t => {
          val playerId = t._1
          val cid = t._2
          val cs = cPool.filter(x => x.id == cid)
          val aPlayerNewStatus: OnePlayerStatus = playersStatus(playerId).addCharacters(cs)
          playersStatus += playerId -> aPlayerNewStatus
        }
      )
      val cards: Seq[Card] = cidS.flatMap(i => Config.genTestCharCards(i)).toSeq
      this.drawDeck = cards ++ this.drawDeck
      true
    }
    else
      false
  }

  def playerDrawCards(maxCards: Int): Boolean = { //抽牌流程
    this.nowPhrase = Phrase.DrawCards
    val nowDrawNum = this.drawDeck.count(_ => true)
    val nowDropDeckNum = this.dropDeck.count(_ => true)
    maxCards * this.nowPlayerNum match {
      case cardsNum
        if cardsNum <= nowDrawNum =>
        val (draws, rest) = sliceToPieces(this.nowPlayerNum, maxCards, this.drawDeck)
        this.drawDeck = rest
        val player2Card: immutable.IndexedSeq[(String, Seq[Card])] = this.seat2Player.toIndexedSeq.sortBy(a => a._1).map(x => x._2) zip draws
        player2Card.foreach(t => {
          val id = t._1
          val addCard = t._2
          val newStatus = this.playersStatus(id).drawAPlayerCards(addCard)
          this.playersStatus += (id -> newStatus)
        })
        true

      case cardsNum
        if cardsNum <= nowDrawNum + nowDropDeckNum && cardsNum > nowDrawNum =>
        val addDraw = Card.shuffleCard(this.dropDeck)
        this.dropDeck = Nil: Seq[Card]
        this.drawDeck = this.drawDeck ++ addDraw
        playerDrawCards(maxCards)
      case _ => false
    }
  }


  def setFirstSeat(playersBid: Array[(String, Int)]): Boolean = {
    val bNum = playersBid.count(_ => true)
    bNum match {
      case bn if bn == this.nowPlayerNum =>
        val nSeat = 1 to nowPlayerNum zip playersBid.sortBy(x => x._2).map(x => x._1)
        this.seat2Player = nSeat.toMap
        true
      case _ => false
    }
  }


  def checkCards(): Boolean = { //TODO 各个玩家检查牌，发动check时的可发动的技能
    this.nowPhrase = Phrase.Check
    true
  }


  def canSpawnCardsToSomebody(who: String, cardIdx: Array[Int], objPlayer: String): (Boolean, Seq[Card]) //接到某玩家出牌消息，消息为当前牌的序号,在多于两人的情况下需指定出牌目标
  = {
    this.nowPhrase = Phrase.FormCards
    val thisStatus = this.playersStatus(who)
    val obStatus = this.playersStatus(objPlayer)
    val thisNeedCounterShape = thisStatus.needCounter.shape
    val obNeedCounterShape = obStatus.needCounter.shape
    val lastSpawnedCard: Seq[SpawnedCard] = thisStatus.needCounter.counterHistorySpawn
    val attacker = lastSpawnedCard.head.who
    val defender = who

    //TODO FormCards的时候可以发动的技能
    //无论是否成功counter 自己的status的needCounter一般都需要清理掉，如果counter成功，新的needCounter会给对手,输出先清掉出牌方的NeedCounter，所以先处理成PerOut
    val thisPerOutStatus = thisStatus.clearNeedCounter()

    def spendHandCardsDo(thisPerOutStatus: OnePlayerStatus, handCards: Seq[Card], cardIdx: Array[Int], who: String): Seq[Card] = { //  出牌过程
      val OutCards = cardIdx.map(x => handCards(x - 1)) //  打出的牌
      val newThisStatus = thisPerOutStatus.spendCards(cardIdx)
      this.playersStatus += (who -> newThisStatus)

      this.nowPhrase = Phrase.Spawn
      //TODO 出牌技能发动

      //检查牌当前牌是否出完，如果出完则本round结束,触发终结效果
      if (newThisStatus.handCards.isEmpty) {

      }
      OutCards
    }


    if (who == this.seat2Player(this.nowTurnSeat)) {
      if (cardIdx.isEmpty) { //是空组当作是PASS操作，触发通常伤害,出牌方收到伤害，出牌记录中最近一个出牌方为攻击者

        genNormalAttackDamage(attacker, defender, thisNeedCounterShape.get)
        (true, Nil: Seq[Card])
      }
      else {
        val handCards = thisStatus.handCards

        val spawnCardsAfterSpawnSkill = cardIdx.map(i => handCards(i - 1))
        val newNeedCounterShape1 = gameplayLib.Card.canShapeCounter(spawnCardsAfterSpawnSkill, thisNeedCounterShape)
        val newNeedCounterShape2 = gameplayLib.Card.canShapeCounter(spawnCardsAfterSpawnSkill, obNeedCounterShape)
        if (newNeedCounterShape1.isEmpty || newNeedCounterShape2.isEmpty) { //说明普通出牌不能counter，会尝试炸弹counter
          val bombShape = Some(Shape(0, thisStatus.bombNeedNum, 0, 0, 0))
          val newNeedBombShape = gameplayLib.Card.canShapeCounter(spawnCardsAfterSpawnSkill, bombShape)
          if (newNeedBombShape.isEmpty) {

            genNormalAttackDamage(attacker, defender, thisNeedCounterShape.get)
            //说明没有符合的牌打出，炸弹也不是，储存一个伤害
            (true, Nil: Seq[Card])
          }
          else { //说明可以是炸弹牌打出


            val OutCards = spendHandCardsDo(thisPerOutStatus, handCards, cardIdx, who)
            (true, OutCards)
          }
        }


        else {
          val OutCards = spendHandCardsDo(thisPerOutStatus, handCards, cardIdx, who)

          (true, OutCards)
        }
      }
    }
    else (false, Nil: Seq[Card])
  }

  def genNormalAttackDamage(attacker: String, obj: String, shape: Shape) = { //伤害计算，攻防计算，存储到伤害map里

  }


  def sliceToPieces[X](piecesNum: Int, pieceMaxRoom: Int, pool: Seq[X]): (Seq[Seq[X]], Seq[X]) = {
    val total = pieceMaxRoom * piecesNum
    val (sPool, rPool) = pool.splitAt(total)
    var temp: Map[Int, Seq[X]] = Map()
    for (i <- 0 until piecesNum) {
      temp += (i -> Nil)
    }

    var index = 0
    for (x <- sPool) {
      val p = index % piecesNum
      val q = x +: temp(p)
      temp += (p -> q)
      index = index + 1
    }
    (temp.values.toSeq, rPool)
  }
}
