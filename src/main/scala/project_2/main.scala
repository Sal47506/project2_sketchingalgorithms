package project_2

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.rdd._


object main{

  val seed = new java.util.Date().hashCode;
  val rand = new scala.util.Random(seed);

  class hash_function(numBuckets_in: Long) extends Serializable {  // a 2-universal hash family, numBuckets_in is the numer of buckets
    val p: Long = 2147483587;  // p is a prime around 2^31 so the computation will fit into 2^63 (Long)
    val a: Long = (rand.nextLong %(p-1)) + 1  // a is a random number is [1,p]
    val b: Long = (rand.nextLong % p) // b is a random number in [0,p]
    val numBuckets: Long = numBuckets_in

    def convert(s: String, ind: Int): Long = {
      if(ind==0)
        return 0;
      return (s(ind-1).toLong + 256 * (convert(s,ind-1))) % p;
    }

    def hash(s: String): Long = {
      return ((a * convert(s,s.length) + b) % p) % numBuckets;
    }

    def hash(t: Long): Long = {
      return ((a * t + b) % p) % numBuckets;
    }

    def zeroes(num: Long, remain: Long): Int =
    {
      if((num & 1) == 1 || remain==1)
        return 0;
      return 1+zeroes(num >> 1, remain >> 1);
    }

    def zeroes(num: Long): Int =        /*calculates #consecutive trialing zeroes  */
    {
      return zeroes(num, numBuckets)
    }
  }

  class four_universal_Radamacher_hash_function extends hash_function(2) {  // a 4-universal hash family, numBuckets_in is the numer of buckets
    override val a: Long = (rand.nextLong % p)   // a is a random number is [0,p]
    override val b: Long = (rand.nextLong % p) // b is a random number in [0,p]
    val c: Long = (rand.nextLong % p)   // c is a random number is [0,p]
    val d: Long = (rand.nextLong % p) // d is a random number in [0,p]

    override def hash(s: String): Long = {     /* returns +1 or -1 with prob. 1/2 */
      val t= convert(s,s.length)
      val t2 = t*t % p
      val t3 = t2*t % p
      return if ( ( ((a * t3 + b* t2 + c*t + b) % p) & 1) == 0 ) 1 else -1;
    }

    override def hash(t: Long): Long = {       /* returns +1 or -1 with prob. 1/2 */
      val t2 = t*t % p
      val t3 = t2*t % p
      return if( ( ((a * t3 + b* t2 + c*t + b) % p) & 1) == 0 ) 1 else -1;
    }
  }

  class BJKSTSketch(bucket_in: Set[(String, Int)], z_in: Int, bucket_size_in: Int) extends Serializable {
    var bucket: Set[(String, Int)] = bucket_in
    var z: Int = z_in
    val BJKST_bucket_size: Int = bucket_size_in

    def this(s: String, z_of_s: Int, bucket_size_in: Int) = {
      this(Set((s, z_of_s)), z_of_s, bucket_size_in)
    }

    def +(that: BJKSTSketch): BJKSTSketch = {
      val combinedBucket = (this.bucket ++ that.bucket).filter { case (_, zVal) =>
        zVal >= math.max(this.z, that.z)
      }

      val newZ = math.max(this.z, that.z)
      val trimmedBucket = if (combinedBucket.size > BJKST_bucket_size) {
        combinedBucket.toSeq.sortBy(-_._2).take(BJKST_bucket_size).toSet
      } else {
        combinedBucket
      }

      new BJKSTSketch(trimmedBucket, newZ, BJKST_bucket_size)
    }

    def add_string(s: String, z_of_s: Int): BJKSTSketch = {
      if (z_of_s >= z) {
        bucket += ((s, z_of_s))
        if (bucket.size > BJKST_bucket_size) {
          val minZ = bucket.minBy(_._2)._2
          bucket = bucket.filter(_._2 > minZ)
          z = bucket.map(_._2).min
        }
      }
      this
    }

    def estimate(): Double = {
      math.pow(2, z) * bucket.size
    }
}

  def tidemark(x: RDD[String], trials: Int): Double = {
    val h = Seq.fill(trials)(new hash_function(2000000000))

    def param0 = (accu1: Seq[Int], accu2: Seq[Int]) => Seq.range(0,trials).map(i => scala.math.max(accu1(i), accu2(i)))
    def param1 = (accu1: Seq[Int], s: String) => Seq.range(0,trials).map( i =>  scala.math.max(accu1(i), h(i).zeroes(h(i).hash(s))) )

    val x3 = x.aggregate(Seq.fill(trials)(0))( param1, param0)
    val ans = x3.map(z => scala.math.pow(2,0.5 + z)).sortWith(_ < _)( trials/2) /* Take the median of the trials */

    return ans
  }


  def BJKST(x: RDD[String], width: Int, trials: Int): Double = {
    val estimates = (0 until trials).map { _ =>
      val hf = new hash_function(1L << 20)  // Assume a 2-universal hash function
      val sketch = x.map { s =>
        val hashed = hf.hash(s)
        val z = Integer.numberOfTrailingZeros(hashed)
        new BJKSTSketch(s, z, width)
      }.reduce(_ + _)
      sketch.estimate()
    }

    val sorted = estimates.sorted
    if (trials % 2 == 1) sorted(trials / 2)
    else (sorted(trials / 2 - 1) + sorted(trials / 2)) / 2.0
  }



  def Tug_of_War(x: RDD[String], width: Int, depth: Int): Long = {
    val estimates = (0 until depth).map { _ =>
      val hashFns = Array.fill(width)(new four_universal_Rademacher_hash_function)
      val sums = hashFns.map { h =>
        x.map(s => h.hash(s).toLong).reduce(_ + _)
      }
      val means = sums.map(sum => sum * sum) // X^2 for each sketch
      means.sum.toDouble / width
    }

    val sorted = estimates.sorted
    if (depth % 2 == 1) sorted(depth / 2).toLong
    else ((sorted(depth / 2 - 1) + sorted(depth / 2)) / 2.0).toLong
  }



  def exact_F0(x: RDD[String]) : Long = {
    val ans = x.distinct.count
    return ans
  }


  def exact_F2(x: RDD[String]): Long = {
    x.map(x => (x, 1)).reduceByKey(_+_).map(x => x._2*x._2).reduce(_+_)
  }



  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("Project_2").getOrCreate()

    if(args.length < 2) {
      println("Usage: project_2 input_path option = {BJKST, tidemark, ToW, exactF2, exactF0} ")
      sys.exit(1)
    }
    val input_path = args(0)

  //    val df = spark.read.format("csv").load("data/2014to2017.csv")
    val df = spark.read.format("csv").load(input_path)
    val dfrdd = df.rdd.map(row => row.getString(0))

    val startTimeMillis = System.currentTimeMillis()

    if(args(1)=="BJKST") {
      if (args.length != 4) {
        println("Usage: project_2 input_path BJKST #buckets trials")
        sys.exit(1)
      }
      val ans = BJKST(dfrdd, args(2).toInt, args(3).toInt)

      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("BJKST Algorithm. Bucket Size:"+ args(2) + ". Trials:" + args(3) +". Time elapsed:" + durationSeconds + "s. Estimate: "+ans)
      println("==================================")
    }
    else if(args(1)=="tidemark") {
      if(args.length != 3) {
        println("Usage: project_2 input_path tidemark trials")
        sys.exit(1)
      }
      val ans = tidemark(dfrdd, args(2).toInt)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Tidemark Algorithm. Trials:" + args(2) + ". Time elapsed:" + durationSeconds + "s. Estimate: "+ans)
      println("==================================")

    }
    else if(args(1)=="ToW") {
       if(args.length != 4) {
         println("Usage: project_2 input_path ToW width depth")
         sys.exit(1)
      }
      val ans = Tug_of_War(dfrdd, args(2).toInt, args(3).toInt)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000
      println("==================================")
      println("Tug-of-War F2 Approximation. Width :" +  args(2) + ". Depth: "+ args(3) + ". Time elapsed:" + durationSeconds + "s. Estimate: "+ans)
      println("==================================")
    }
    else if(args(1)=="exactF2") {
      if(args.length != 2) {
        println("Usage: project_2 input_path exactF2")
        sys.exit(1)
      }
      val ans = exact_F2(dfrdd)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Exact F2. Time elapsed:" + durationSeconds + "s. Estimate: "+ans)
      println("==================================")
    }
    else if(args(1)=="exactF0") {
      if(args.length != 2) {
        println("Usage: project_2 input_path exactF0")
        sys.exit(1)
      }
      val ans = exact_F0(dfrdd)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Exact F0. Time elapsed:" + durationSeconds + "s. Estimate: "+ans)
      println("==================================")
    }
    else {
      println("Usage: project_2 input_path option = {BJKST, tidemark, ToW, exactF2, exactF0} ")
      sys.exit(1)
    }

  }
}

