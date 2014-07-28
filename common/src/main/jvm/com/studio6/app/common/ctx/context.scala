package com.studio6.app.common.ctx

import scala.collection.mutable

object implicits {
  implicit def optExecutionContext = ExecutionContext.optional()
  implicit def executionContext = ExecutionContext.getOrCreate()
}

trait ExecutionContextHelper {

  def inContext[T](contextAttributes: (String, Any)*)(fn: => T): T = {
    val cas: Map[String, Any] = Map(contextAttributes: _*)
    inContext(cas)(fn)
  }

  def inContext[T](contextAttributes: Map[String, Any] = Map.empty)(fn: => T): T = {
    val ctx = ExecutionContext.getOrCreate().setAttributes(contextAttributes)
    ctx.incRefCount()
    try {
      fn
    } finally {
      ctx.decRefCount()
      if (ctx.refCount == 0) {
        ExecutionContext.remove()
      }
    }
  }

  def withContext[T](fn: ExecutionContext => T): T = {
    val ctx = ExecutionContext.getOrCreate()
    ctx.incRefCount()
    try {
      fn(ctx)
    } finally {
      ctx.decRefCount()
      if (ctx.refCount == 0) {
        ExecutionContext.remove()
      }
    }
  }

  def optionalContext(): Option[ExecutionContext] = ExecutionContext.optional()
}

object ExecutionContext {
  private val executionContexts = new ThreadLocal[ExecutionContext]

  def set(context: ExecutionContext): ExecutionContext = {
    executionContexts.set(context)
    context
  }

  def remove() {
    executionContexts.remove()
  }

  def getNew(): ExecutionContext = optional() match {
    case Some(context) => error("Unexpected execution context is present")
    case None => set(new ExecutionContext)
  }

  def optional(): Option[ExecutionContext] = get(false)

  def getRequired(): ExecutionContext = optional() match {
    case Some(context) => context
    case None => error("Required execution context is not set")
  }

  def getOrCreate(): ExecutionContext = get(true).get

  private def get(create: Boolean): Option[ExecutionContext] = {
    val context = executionContexts.get().asInstanceOf[ExecutionContext]

    if (context == null && create) {
      Some(set(new ExecutionContext()))
    } else {
      Option(context)
    }
  }
}

class ExecutionContext(private val attributes: mutable.Map[String, Any] = mutable.Map.empty) {
  var refCount = 0

  def incRefCount(): Unit = { refCount = refCount + 1 }
  def decRefCount(): Unit = { refCount = refCount - 1 }

  def setAttribute(name: String, value: Any): ExecutionContext = {
    attributes.put(name, value);
    this
  }

  def setAttributes(attrs: Map[String, Any]): ExecutionContext = {
    attrs.foreach(t => setAttribute(t._1, t._2))
    this
  }

  def appendAttribute(name: String, value: Any): ExecutionContext = {
    attributes.get(name) match {
      case Some(vs) => vs match {
        case vs: Seq[Any] => setAttribute(name, vs :+ value)
      }
      case None => setAttribute(name, Seq(value))
    }
  }

  def optionalAttribute(name: String): Option[Any] = attributes.get(name)

  def optionalAttribute[T](name: String, clazz: Class[T]): Option[T] = attributes.get(name).map(_.asInstanceOf[T])
}
