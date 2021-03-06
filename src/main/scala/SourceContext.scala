package org.scala_lang.virtualized

import scala.reflect.macros.blackbox.Context
import scala.language.experimental.macros

import scala.collection.mutable.HashMap

/** A SourceContext is a descriptor for the invocation of a method that takes
 *  an implicit parameter of type SourceContext. It provides information about the
 *  location in the source where the method is invoked, such as the source file name
 *  and line number.
 */

trait SourceContext {

  /** The full path of the source file of the calling location
   */
  def path: String

  /** The name of the source file of the calling location
   */
  def fileName: String

  /** The line number of the calling location
   */
  def line: Int

  /** The column of the calling location
   */
  def column: Int

  /** The name of the method being called
   */
  def methodName: String

  /** The name of the value / variable instantiated to hold the result of the method
   */
  def assignedVariable: Option[String]


  // Not yet supported
  // def parent: Option[SourceContext]


  override def toString(): String = fileName + ":" + line + ":" + column


  // def update(context: SourceContext): SourceContext
  // def receiver: Option[String]
  // def allContexts: List[List[(String, Int)]]
}

object SourceContext {

  implicit def _sc: SourceContext = macro SourceContextMacro.impl

  def apply(path: String, fileName: String, line: Int, column: Int, methodName: String, assignedVariable: Option[String]): SourceContext =
    ConcreteSourceContext(path, fileName, line, column, methodName, assignedVariable)

  private case class ConcreteSourceContext(val path: String, val fileName: String, val line: Int, val column: Int, val methodName: String, val assignedVariable: Option[String]) extends SourceContext {
    override def toString(): String = fileName + ":" + line + ":" + column
  }
}

//using the available information from the macro Context, rebuild the SourceContext previously generated by the compiler
private object SourceContextMacro {

  // HACK: Mantain a hashmap for the number of times we've seen a specific tight string and the number of operands in that string
  // This is pants-on-head stupid and needs to be replaced with a simple lookup once that's available
  var visits = HashMap[String, Int]()
  var depths = HashMap[String, Int]()

  def impl(c: Context): c.Expr[SourceContext] = {
    import c.universe._
    val pos = c.enclosingPosition
    val path = pos.source.path
    val filename = pos.source.file.name
    val line = pos.line
    val column = pos.column

    val owner = c.internal.enclosingOwner
    val assignedVariable = if (!owner.isMethod && owner.isTerm) Some(owner.name.toString) else None


    def isBreak(x: Char) = x == ' ' || x == '.' || x == '(' || x == ')' || x == ';'

    // HACK: Haven't yet found a simpler way of determining which method call this is in reference to
    // Macros have a lot of support for enclosing context, but apparently very little for immediate context of implicit call
    // However, we can easily get the source line and general method call location using the column number
    // We can then reparse the immediate area (stopping at parentheses, spaces, periods, semicolons) to get the method
    // Also include try-catch blocks for now just in case we somehow mess up when splicing the string

    // TODO: pos.source.lineToString occassionally doesn't match up with the given column?
    val methodName = if (line > 0 && column > 0) {
      try {
        val str = pos.source.lineToString(line-1)

        var start = column-1
        var end = column-1
        if (isBreak(str(column-1))) start -= 1

        while (start >= 0 && !isBreak(str(start))) start -= 1
        while (end < str.length && !isBreak(str(end))) end += 1
        if (start < 0 || isBreak(str(start))) start = start + 1
        if (end >= str.length || isBreak(str(end))) end = end - 1
        val tight = str.slice(start, end+1).trim

        // The scala compiler MUST have had this information at some point to give us the correct column,
        // but it seems there's no way to get it?
        val tightTree = c.parse(tight)
        // c.info(c.enclosingPosition, "Tight: " + tight, true)
        // c.info(c.enclosingPosition, showRaw(tightTree), true)

        def extractMethod(tree: Tree, depth: Int = 0): String = tree match {
          case Select(term,func) => func.decodedName.toString //tight.replace(name,"")
          case Apply(Select(term,func), _) if depth == 0 => func.decodedName.toString
          case Apply(Select(term,func), _) => extractMethod(term, depth-1)
          case _ => tight
        }
        /**
        Given a call val x = a+b-c

        We see something for a+b-c that looks like:
              Apply(
                Select(
                  Apply(
                    Select(a, TermName(+)),
                    List(b)
                  ),
                  TermName(-)
                ),
                List(c)
              )

          Essentially means the operators are in a reversed linked list,
          so we need to know depth of list (number of times we'll see this string)
          and we need to count the number of times we've seen this same string so far
        **/
        def getMethodDepth(tree: Tree, depth: Int = 0): Int = tree match {
          case Select(term,func) => depth+1
          case Apply(Select(term,func), _) => getMethodDepth(term, depth+1)
          case _ => depth
        }

        val depth = depths.getOrElseUpdate(tight, getMethodDepth(tightTree) - 1)
        val visit = visits.getOrElse(tight, 0)
        visits += tight -> (visit + 1)

        extractMethod(tightTree, depth-visit) // Go to the last operator for the first, and so on
      }
      catch {case e:Throwable =>
        "<unknown>"
      }
    }
    else {
      "<unknown>"
    }

    // macroApplication doesn't have what we need here (it just gives a pointer to the def _sc line above)
    //val methodName = "<unknown>" //c.macroApplication.symbol.name.toString //c.internal.enclosingOwner.name.toString

    // enclosingDef is deprecated (and also gives a completely wrong answer)
    //val assignedVariable = Some(c.enclosingDef.name.toString)
    c.Expr(q"SourceContext($path, $filename, $line, $column, $methodName, $assignedVariable)")
  }
}
