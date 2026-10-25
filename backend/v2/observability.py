"""Optional OTel tracing plus bounded-cardinality operational metrics."""
import json
import logging
from opentelemetry import trace
from v2.settings import value

logger=logging.getLogger('sellerpilot')


def configure(app,engine):
    if value('OTEL_ENABLED','0')!='1': return
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace.export import BatchSpanProcessor,ConsoleSpanExporter
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
    from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
    provider=TracerProvider(resource=Resource.create({'service.name':value('OTEL_SERVICE_NAME','sellerpilot-v2')}))
    provider.add_span_processor(BatchSpanProcessor(ConsoleSpanExporter()))
    trace.set_tracer_provider(provider)
    if app is not None:FastAPIInstrumentor.instrument_app(app,excluded_urls='/health,/metrics')
    SQLAlchemyInstrumentor().instrument(engine=engine)


def span(name,**attributes):
    return trace.get_tracer('sellerpilot').start_as_current_span(name,attributes=attributes)


def log_event(kind,**fields):
    logger.info(json.dumps({'event':kind,**fields},ensure_ascii=False,default=str))
